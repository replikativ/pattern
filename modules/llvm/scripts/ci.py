#!/usr/bin/env python3
"""Opt-in LLVM acceptance; never deploys or executes arbitrary imported code.

Run from any directory with Clojure CLI, Java 17+, LLVM 21 and a C++ compiler
installed. The native integration tests execute only the repository's fixtures.
This script deliberately builds the normal root JAR to check module separation.
It is not a reproducible native binary build or a large-module memory benchmark.
"""

import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET
import zipfile


ROOT = Path(__file__).resolve().parents[3]
MODULE = ROOT / "modules" / "llvm"
RECEIPT = MODULE / "target" / "ci" / "receipt.json"


def run(command, *, cwd=ROOT, env=None, capture=False):
    print(json.dumps({"command": command, "cwd": str(cwd)}), flush=True)
    environment = dict(os.environ if env is None else env)
    # Also constrain JVMs forked by tools.build's AOT task. Explicit caller JVM
    # settings remain authoritative; CLI -J options still constrain top-levels.
    environment.setdefault("JAVA_TOOL_OPTIONS", "-Xmx512m -Xss4m")
    result = subprocess.run(command, cwd=cwd, env=environment, check=True,
                            text=True, stdout=subprocess.PIPE if capture else None)
    return result.stdout.strip() if capture else None


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def consumer_smoke():
    # In particular, do not use root aliases or add root/src manually. The module
    # must resolve its own ../.. Pattern dependency from an unrelated project.
    with tempfile.TemporaryDirectory(prefix="pattern-llvm-consumer-") as temporary:
        directory = Path(temporary)
        coordinate = json.dumps(str(MODULE))  # JSON string is also an EDN string.
        (directory / "deps.edn").write_text(
            '{:paths [] :deps {pattern.llvm/adapter {:local/root ' + coordinate + '}}}\n',
            encoding="utf-8")
        environment = dict(os.environ,
                           PATTERN_LLVM_BRIDGE=str(MODULE / "target" / "bridge"),
                           PATTERN_LLVM_FIXTURE=str(MODULE / "test" / "fixtures" / "graph.ll"),
                           PATTERN_LLVM_OUTPUT=str(directory / "edited.ll"))
        smoke = r'''
(require '[pattern :as pattern] '[pattern.llvm :as llvm] '[pattern.llvm.graph :as graph])
(let [base (llvm/import-file (System/getenv "PATTERN_LLVM_BRIDGE")
                            (System/getenv "PATTERN_LLVM_FIXTURE"))
      named (fn [s n] (first (filter #(= n (:name %)) (vals (:nodes s)))))
      identity (named base "identity") answer (named base "answer")
      match (pattern/compile-pattern '{:kind "instruction" :opcode "add"})
      child (graph/replace-uses base (:id identity) (:id answer))
      output (System/getenv "PATTERN_LLVM_OUTPUT")
      receipt (llvm/export-file! (System/getenv "PATTERN_LLVM_BRIDGE") child output)
      restored (llvm/import-file (System/getenv "PATTERN_LLVM_BRIDGE") output)]
  (assert (match answer))
  (assert (= 2 (:edits receipt)))
  (assert (= [(:id identity) (:id identity)] (:operands (named base "twice"))))
  (assert (= [(:id answer) (:id answer)] (:operands (named restored "twice"))))
  (println {:standalone-consumer :passed :nodes (count (:nodes restored))}))
(shutdown-agents)
'''
        run(["clojure", "-Srepro", "-J-Xmx512m", "-J-Xss4m", "-M", "-e", smoke],
            cwd=directory, env=environment)


def package_separation():
    run(["clojure", "-Srepro", "-J-Xmx512m", "-T:build", "clean"])
    run(["clojure", "-Srepro", "-J-Xmx512m", "-T:build", "jar"])
    jars = list((ROOT / "target").glob("pattern-*.jar"))
    if len(jars) != 1:
        raise RuntimeError("Expected exactly one fresh normal Pattern JAR")
    with zipfile.ZipFile(jars[0]) as archive:
        entries = archive.namelist()
        forbidden = [name for name in entries
                     if name.startswith(("pattern/llvm", "modules/llvm/"))
                     or name.endswith(("/bridge", ".so", ".dylib", ".dll"))]
        if forbidden:
            raise RuntimeError("Optional module leaked into normal JAR: " + repr(forbidden[:8]))
        pom = "META-INF/maven/org.replikativ/pattern/pom.xml"
        document = ET.fromstring(archive.read(pom))
        namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
        dependencies = {(node.findtext("m:groupId", namespaces=namespace),
                         node.findtext("m:artifactId", namespaces=namespace))
                        for node in document.findall("./m:dependencies/m:dependency", namespace)}
        if dependencies & {("org.clojure", "data.json"), ("io.lacuna", "bifurcan")}:
            raise RuntimeError("Optional dependencies leaked into normal Pattern POM")
    return {"jar": jars[0].name, "sha256": sha256(jars[0]),
            "optional-code-excluded": True, "optional-pom-dependencies-excluded": True}


def main():
    started = time.monotonic()
    receipt = {"format": "pattern.llvm.ci/1", "completed": [], "status": "running"}
    try:
        sources = [ROOT / "deps.edn", ROOT / "build.clj", ROOT / ".github/workflows/llvm.yml"]
        sources.extend(path for path in MODULE.rglob("*")
                       if path.is_file() and "target" not in path.relative_to(MODULE).parts
                       and "__pycache__" not in path.parts)
        receipt["sources-sha256"] = {str(path.relative_to(ROOT)): sha256(path)
                                     for path in sorted(sources)}
        receipt["base-git-head"] = run(["git", "rev-parse", "HEAD"], capture=True)
        llvm_config = os.environ.get("LLVM_CONFIG", "llvm-config")
        version = run([llvm_config, "--version"], capture=True)
        if version.split(".", 1)[0] != "21":
            raise RuntimeError("Expected LLVM major21, found " + version)
        receipt["llvm-version"] = version
        receipt["clojure-description"] = run(["clojure", "-Srepro", "-Sdescribe"], capture=True)
        receipt["java-version"] = run(["java", "--version"], capture=True)
        if sys.platform == "linux" and Path("/usr/bin/dpkg-query").is_file():
            receipt["llvm-packages"] = run(
                ["dpkg-query", "-W", "-f=${Package} ${Version}\n", "llvm-21", "llvm-21-dev", "clang-21"],
                capture=True)
        run([sys.executable, str(MODULE / "native" / "build.py")])
        receipt["bridge-sha256"] = sha256(MODULE / "target" / "bridge")
        receipt["completed"].append("native-build")
        # The shared runner automatically includes future registered contract or
        # audit tests; no second, independently maintained test list is used here.
        for optional in (False, True):
            aliases = "-M:llvm:llvm-test" if not optional else "-M:llvm:llvm-bifurcan:llvm-test"
            run(["clojure", "-Srepro", "-J-Xmx512m", "-J-Xss4m", aliases]
                + (["bifurcan"] if optional else []))
            receipt["completed"].append("bifurcan-suite" if optional else "default-suite")
        consumer_smoke()
        receipt["completed"].append("external-local-root-consumer")
        receipt["package"] = package_separation()
        receipt["completed"].append("normal-jar-separation")
        receipt["status"] = "passed"
    except Exception as failure:
        receipt["status"] = "failed"
        receipt["failure-type"] = type(failure).__name__
        raise
    finally:
        receipt["elapsed-seconds"] = round(time.monotonic() - started, 3)
        RECEIPT.parent.mkdir(parents=True, exist_ok=True)
        RECEIPT.write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
