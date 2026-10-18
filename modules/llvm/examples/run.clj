(ns pattern.llvm.examples.run
  "REPL-driven, bounded native compilation example. Run from the Pattern root."
  (:refer-clojure :exclude [run!])
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [pattern :as pattern]
            [pattern.llvm :as llvm]
            [pattern.llvm.graph :as graph]
            [pattern.llvm.support :as support])
  (:import [java.nio.file Files]))

(load-file "modules/llvm/examples/i64.clj")
(load-file "modules/llvm/examples/primes.clj")
(require '[pattern.llvm.examples.i64 :as compiler]
         '[pattern.llvm.examples.primes :as primes])

(defn- check! [condition message data]
  (when-not condition (throw (ex-info message data))))
(defn- command! [& args]
  (let [result (apply shell/sh (map str args))]
    (check! (zero? (:exit result)) "Native example command failed" {:command args :result result})
    (str/trim (:out result))))
(defn- read-definitions [path]
  (with-open [reader (java.io.PushbackReader. (io/reader path :encoding "UTF-8"))]
    (loop [definitions []]
      (let [form (edn/read {:eof ::end} reader)]
        (cond (= ::end form) definitions
              (= 'ns (first form)) (recur definitions)
              (= 'defn (first form)) (recur (conj definitions form))
              :else (throw (ex-info "Unexpected example source form" {:form form})))))))

(def semantic-probes
  ;; Tests target compiler hazards: parallel recur arguments, nested loop exits,
  ;; lexical binding, wrapping arithmetic, signed division and all comparisons.
  '[[(defn parallel [n]
       (loop [i n a 3 b 8]
         (if (= i 0) (unchecked-subtract a b)
             (recur (unchecked-subtract i 1) b a)))) [0] -5]
    [(defn parallel-odd [n]
       (loop [i n a 3 b 8]
         (if (= i 0) (unchecked-subtract a b)
             (recur (unchecked-subtract i 1) b a)))) [3] 5]
    [(defn nested [n]
       (loop [i 0 total 0]
         (if (>= i n) total
             (let [part (loop [j 0 sum 0]
                          (if (> j i) sum (recur (unchecked-inc j) (unchecked-add sum j))))]
               (recur (unchecked-inc i) (unchecked-add total part)))))) [4] 10]
    [(defn wrapping [a b] (unchecked-add (unchecked-multiply a b) 1)) [9223372036854775807 1] -9223372036854775808]
    [(defn bits [a b] (bit-xor (bit-or a b) (bit-and a b))) [-7 13] -12]
    [(defn quotient [a b] (quot a b)) [-17 5] -3]
    [(defn remainder [a b] (rem a b)) [-17 5] -2]
    [(defn bindings [n] (let [a (unchecked-inc n) b (unchecked-multiply a 3)]
                         (if (<= b 12) (if (not= a 0) b 99) 88))) [3] 12]])

(defn- c-integer [n]
  (if (= Long/MIN_VALUE n) "(-INT64_C(9223372036854775807)-INT64_C(1))"
      (if (neg? n) (str "(-INT64_C(" (- n) "))") (str "INT64_C(" n ")"))))

(defn- semantic-checks! [directory bridge clang]
  (let [definitions (mapv first semantic-probes)
        assembly (compiler/compile-module definitions)
        source (io/file directory "semantics.ll") output (io/file directory "semantics-export.ll")
        driver (io/file directory "semantics.c") binary (io/file directory "semantics")]
    ;; Evaluate only these fixed, repository-owned test forms as the reference.
    (binding [*ns* (the-ns 'pattern.llvm.examples.run)]
      (doseq [[definition arguments expected] semantic-probes]
        (eval definition)
        (check! (= expected (apply (ns-resolve *ns* (second definition)) arguments))
                "Incorrect Clojure probe expectation" {:function (second definition)})))
    (spit source assembly :encoding "UTF-8")
    (llvm/export-file! bridge (llvm/import-file bridge (str source)) (str output))
    (spit driver
          (str "#include <stdint.h>\n#include <inttypes.h>\n#include <stdio.h>\n"
               (str/join "\n" (for [[[_ name params]] semantic-probes]
                                  (str "extern int64_t clj_" (str/replace (str name) "-" "_") "("
                                       (str/join ", " (repeat (count params) "int64_t")) ");")))
               "\nint main(void) {\n"
               (str/join "\n" (for [[[_ name] arguments] semantic-probes]
                                  (str "printf(\"%\" PRId64 \"\\n\", clj_" (str/replace (str name) "-" "_")
                                       "(" (str/join ", " (map c-integer arguments)) "));")))
               "\nreturn 0;\n}\n") :encoding "UTF-8")
    (command! clang "-O3" output driver "-o" binary)
    (let [actual (mapv parse-long (str/split-lines (command! binary))) expected (mapv #(nth % 2) semantic-probes)]
      (check! (= expected actual) "Native compiler semantic probes differ" {:expected expected :actual actual})
      {:cases (count semantic-probes) :results actual})))

(defn run!
  "Create a NEW output directory, compile the same source in Clojure and LLVM,
   persistently rewrite it, optimize/codegen, and compare two native executables.
   Every native command operates only on these repository-owned examples."
  [output-directory]
  (let [directory (.getCanonicalFile (io/file output-directory))
        _ (Files/createDirectories (.toPath (.getParentFile directory)) (make-array java.nio.file.attribute.FileAttribute 0))
        _ (Files/createDirectory (.toPath directory) (make-array java.nio.file.attribute.FileAttribute 0))
        bridge (.getCanonicalPath (io/file "modules/llvm/target/bridge"))
        bindir (command! (or (System/getenv "LLVM_CONFIG") "llvm-config-21") "--bindir")
        clang (io/file bindir "clang") opt (io/file bindir "opt")
        source-path "modules/llvm/examples/primes.clj"
        assembly (compiler/compile-module (read-definitions source-path))
        source (io/file directory "primes.ll") rewritten (io/file directory "rewritten.ll")
        optimized (io/file directory "optimized.ll")
        plain-binary (io/file directory "primes-unoptimized") native-binary (io/file directory "primes")
        _ (spit source assembly :encoding "UTF-8")
        base (llvm/import-file bridge (str source))
        swap-add (pattern/rule '(?:as node {:kind "instruction" :opcode "add" :operands [?a ?b]})
                   (assoc node :operands [b a]))
        adds (sort-by :id (filter #(= "add" (:opcode %)) (vals (:nodes base))))
        _ (support/require-support! base {:nodes (mapv :id adds) :operation :set-operand
                                         :fields [:nuw :nsw] :metadata :none :debug-records :none})
        _ (check! (every? #(and (false? (:nuw %)) (false? (:nsw %))) adds)
                  "Example expects wrapping addition without overflow flags" {})
        child (reduce #(graph/rewrite-node %1 (:id %2) swap-add) base adds)
        export (llvm/export-file! bridge child (str rewritten))
        _ (check! (= (:nodes base) (get-in child [:origin :base :nodes])) "Original snapshot changed" {})
        _ (command! opt "-S" "-passes=default<O3>" rewritten "-o" optimized)
        after-optimization (llvm/import-file bridge (str optimized))
        driver (io/file "modules/llvm/examples/driver.c")
        _ (command! clang "-O0" source driver "-o" plain-binary)
        _ (command! clang "-O0" optimized driver "-o" native-binary)
        limits [-10 0 1 2 3 10 100 1000 10000 100000]
        results (mapv (fn [limit]
                        (let [reference [(primes/count-primes limit) (primes/sum-primes limit)]
                              run (fn [binary] (mapv parse-long (str/split (command! binary limit) #"\s+")))
                              plain (run plain-binary) native (run native-binary)]
                          (check! (= reference plain native) "Native and Clojure results differ"
                                  {:limit limit :clojure reference :native native :plain plain})
                          {:limit limit :prime-count (first native) :prime-sum (second native)})) limits)
        probes (semantic-checks! directory bridge clang)
        negative-forms '[[(defn checked [x] (+ x 1))]
                         [(defn object [x] [x])]
                         [(defn wrong [x] (if x 1 0))]
                         [(defn wrong-recur [x] (loop [n x] (unchecked-add (recur n) 1)))]
                         [(defn shadow [x] (let [unchecked-add x] (unchecked-add x 1)))]
                         [(defn varargs [& xs] 1)]]
        _ (doseq [forms negative-forms]
            (check! (try (compiler/compile-module forms) false (catch clojure.lang.ExceptionInfo _ true))
                    "Unsupported source unexpectedly accepted" {:forms forms}))
        report {:format "pattern.llvm.native-primes/1" :llvm-version (:llvm-version base)
                :source-sha256 (graph/sha256 (slurp source-path :encoding "UTF-8"))
                :compiler-sha256 (graph/sha256 (slurp "modules/llvm/examples/i64.clj" :encoding "UTF-8"))
                :pattern-edit-count (:edits export) :baseline-instructions (:instructions (support/support-report base))
                :optimized-instructions (:instructions (support/support-report after-optimization))
                :results results :semantic-probes probes :unsupported-source-rejections (count negative-forms)
                :native-executables [(.getName plain-binary) (.getName native-binary)]
                :performance-comparison-claimed false}]
    (spit (io/file directory "observation.edn") (with-out-str (pprint/pprint report)) :encoding "UTF-8")
    report))
