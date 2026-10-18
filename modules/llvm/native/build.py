#!/usr/bin/env python3
"""Build the optional LLVM21 bridge with one compiler process, never LLVM itself."""
import os
from pathlib import Path
import shlex
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent
TARGET = HERE.parent / "target" / "bridge"
LLVM_CONFIG = os.environ.get("LLVM_CONFIG", "llvm-config")
CXX = os.environ.get("CXX", "clang++")


def config(*args):
    return subprocess.check_output([LLVM_CONFIG, *args], text=True).strip()


def main():
    version = config("--version")
    if version.split(".", 1)[0] != "21":
        raise SystemExit("This bridge profile requires LLVM major21; found " + version)
    flags = shlex.split(config("--cxxflags"))
    link = shlex.split(config("--ldflags", "--system-libs", "--libs", "core", "irreader", "support"))
    TARGET.parent.mkdir(parents=True, exist_ok=True)
    # A failed/interrupted compiler must never damage the last working bridge.
    # Build privately on the same filesystem, then atomically replace only after
    # the compiler exits successfully. No durability/fsync guarantee is implied.
    with tempfile.TemporaryDirectory(prefix=".bridge-build-", dir=TARGET.parent) as directory:
        candidate = Path(directory) / "bridge"
        command = [CXX, *flags, "-O0", str(HERE / "bridge.cpp"), "-o", str(candidate), *link]
        print("LLVM " + version + "; " + shlex.join(command), flush=True)
        subprocess.run(command, check=True)
        os.replace(candidate, TARGET)
    print(str(TARGET), flush=True)


if __name__ == "__main__":
    main()
