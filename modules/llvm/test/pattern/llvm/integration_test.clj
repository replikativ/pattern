(ns pattern.llvm.integration-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [pattern :as pattern]
            [pattern.llvm :as llvm]
            [pattern.llvm.graph :as graph])
  (:import [java.nio.file Files]))

(def bridge (.getCanonicalPath (io/file "modules/llvm/target/bridge")))
(def fixture (.getCanonicalPath (io/file "modules/llvm/test/fixtures/graph.ll")))
(defonce imported (delay (llvm/import-file bridge fixture)))
(defn named [snapshot name]
  (first (filter #(= name (:name %)) (vals (:nodes snapshot)))))
(defn tool [name]
  (str (str/trim (:out (shell/sh (or (System/getenv "LLVM_CONFIG") "llvm-config") "--bindir"))) "/" name))
(defn tool-path [name] (tool name))
(defn in-directory [f]
  (let [directory (.toFile (Files/createTempDirectory "pattern-llvm-test-"
                            (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try (f directory)
         (finally (doseq [file (reverse (file-seq directory))] (io/delete-file file true))))))

(deftest native-import-preserves-graph-structure
  (let [snapshot @imported
        twice (named snapshot "twice") identity (named snapshot "identity")
        phi (named snapshot "i")
        merge-block (named snapshot "merge")
        match-add (pattern/compile-pattern '{:kind "instruction" :opcode "add" :operands [?left ?right]})]
    (is (= #{[(:id twice) 0] [(:id twice) 1]} (graph/uses snapshot (:id identity))))
    (is (= "phi" (:opcode phi)))
    (is (= 2 (count (:incoming phi))))
    (is (= 2 (count (graph/incoming-edges snapshot (:id merge-block)))))
    (is (false? (get-in snapshot [:coverage :typed-metadata])))
    (is (= (:operands twice) ((juxt :left :right) (match-add twice))))
    (is (:nsw (named snapshot "answer")))
    (is (:nuw (named snapshot "answer")))
    (is (some #(and (= "integer" (:kind %)) (= 257 (:bits %))) (vals (:types snapshot))))
    (is (some #(and (= "constant-int" (:kind %))
                   (= "1606938044258990275541962092341162602522202993782792835301376" (:value %)))
              (vals (:nodes snapshot))))
    (is (re-find #"distinct !\{!0" (get-in snapshot [:origin :assembly])))))

(deftest compiler-assembly-entry-point
  (let [snapshot (llvm/import-assembly bridge (slurp fixture :encoding "UTF-8"))]
    (is (= (select-keys @imported [:nodes :types :module])
           (select-keys snapshot [:nodes :types :module])))
    (is (= (get-in @imported [:origin :graph-sha256]) (get-in snapshot [:origin :graph-sha256])))
    (in-directory
      (fn [directory]
        (let [output (str (io/file directory "compiler-output.ll"))]
          (is (= 0 (:edits (llvm/export-file! bridge snapshot output))))
          (is (= 84 (:exit (shell/sh (tool-path "lli") output))))))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be a string"
        (llvm/import-assembly "/not-an-executable" nil)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"rejected"
        (llvm/import-assembly bridge "define i64 @invalid() { ret i32 0 }")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"output limit"
        (llvm/import-assembly bridge "define i64 @constant() { ret i64 7 }" {:max-output-bytes 1}))))

(deftest native-verified-persistent-forks
  (in-directory
    (fn [directory]
      (let [base @imported
            identity (named base "identity") answer (named base "answer") twice (named base "twice")
            nineteen (first (filter #(= "19" (:value %)) (vals (:nodes base))))
            folded (graph/replace-uses base (:id identity) (:id answer))
            sibling (graph/set-operand base (:id twice) 0 (:id nineteen))
            folded-path (str (io/file directory "folded.ll")) sibling-path (str (io/file directory "sibling.ll"))]
        (is (= 2 (:edits (llvm/export-file! bridge folded folded-path))))
        (is (= 1 (:edits (llvm/export-file! bridge sibling sibling-path))))
        (is (= 84 (:exit (shell/sh (tool-path "lli") folded-path))))
        (is (= 61 (:exit (shell/sh (tool-path "lli") sibling-path))))
        (is (= [(:id identity) (:id identity)] (:operands (named base "twice"))))
        (is (identical? (named base "loop") (named folded "loop")))
        (let [again (llvm/import-file bridge folded-path)]
          (is (= [(:id answer) (:id answer)] (:operands (named again "twice")))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"rejected"
                             (llvm/export-file! bridge folded folded-path)))
        (is (= 84 (:exit (shell/sh (tool-path "lli") folded-path))))))))

(deftest ordered-repeated-slot-and-dominance
  (in-directory
    (fn [directory]
      (let [base @imported twice (named base "twice") answer (named base "answer") identity (named base "identity")
            repeat-slot (-> base (graph/set-operand (:id twice) 0 (:id answer))
                            (graph/set-operand (:id twice) 0 (:id identity)))
            output (str (io/file directory "sequential.ll"))
            invalid (graph/set-operand base (:id answer) 0 (:id identity))
            invalid-path (str (io/file directory "invalid.ll"))]
        (is (= 2 (:edits (llvm/export-file! bridge repeat-slot output))))
        (is (= 84 (:exit (shell/sh (tool-path "lli") output))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"rejected"
                             (llvm/export-file! bridge invalid invalid-path)))
        (is (not (.exists (io/file invalid-path))))))))

(deftest bitcode-and-capsule-roundtrip
  (in-directory
    (fn [directory]
      (let [output (str (io/file directory "unmodified.ll")) bitcode (str (io/file directory "input.bc"))]
        (is (zero? (:exit (shell/sh (tool-path "llvm-as") fixture "-o" bitcode))))
        (let [snapshot (llvm/import-file bridge bitcode)]
          (is (= (:nodes @imported) (:nodes snapshot)))
          (is (= (:types @imported) (:types snapshot)))
          (is (= 0 (:edits (llvm/export-file! bridge snapshot output))))
          (is (= (:nodes snapshot) (:nodes (llvm/import-file bridge output)))))))))

(deftest process-output-and-option-limits
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"output limit"
                       (llvm/import-file bridge fixture {:max-output-bytes 1})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Timeout must"
                       (llvm/import-file "/not-an-executable" fixture {:timeout-ms Long/MAX_VALUE}))))

(deftest unicode-controls-and-byte-names-roundtrip
  (in-directory
    (fn [directory]
      (let [snapshot (llvm/import-file bridge "modules/llvm/test/fixtures/names.ll")
            output (str (io/file directory "names.ll"))]
        (is (some #(and (:name %) (.contains ^String (:name %) "🦉")) (vals (:nodes snapshot))))
        (is (some :name-bytes-hex (vals (:nodes snapshot))))
        (is (.contains ^String (get-in snapshot [:module :source-filename]) "\u2028"))
        (is (= 0 (:edits (llvm/export-file! bridge snapshot output))))
        (is (= (:nodes snapshot) (:nodes (llvm/import-file bridge output))))))))

(deftest native-source-profile-and-stale-patches-rejected
  (in-directory
    (fn [directory]
      (let [snapshot @imported answer (named snapshot "answer") twice (named snapshot "twice")
            changed (graph/set-operand snapshot (:id twice) 0 (:id answer))
            request (graph/export-request changed)
            input (io/file directory "baseline.ll") patch (io/file directory "patch.json")
            output (io/file directory "forbidden.ll")]
        (spit input (get-in snapshot [:origin :assembly]) :encoding "UTF-8")
        (doseq [bad [(assoc request :source-sha256 (apply str (repeat 64 "0")))
                     (assoc request :graph-sha256 (apply str (repeat 64 "0")))
                     (assoc request :llvm-version "21.1.999")
                     (assoc-in request [:edits 0 :from] (:id answer))
                     (update-in request [:edits 0 :user] double)]]
          (spit patch (json/write-str bad) :encoding "UTF-8")
          (is (not (zero? (:exit (shell/sh bridge "rewrite" (str input) (str patch) (str output))))))
          (is (not (.exists output))))))))

(deftest arbitrary-byte-source-filename-roundtrip
  (in-directory
    (fn [directory]
      (let [input (io/file directory "bytes.ll") output (str (io/file directory "bytes-out.ll"))]
        (spit input "source_filename = \"source\\FF\"\ndefine i32 @main() { ret i32 0 }\n" :encoding "UTF-8")
        (let [snapshot (llvm/import-file bridge (str input))]
          (is (= "736f75726365ff" (get-in snapshot [:module :source-filename-bytes-hex])))
          (is (not (contains? (:module snapshot) :source-filename)))
          (is (= 0 (:edits (llvm/export-file! bridge snapshot output))))
          (is (= (:module snapshot) (:module (llvm/import-file bridge output)))))))))
