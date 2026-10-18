(ns pattern.llvm
  "Optional LLVM subprocess adapter. Requiring this namespace loads no native
   library or compiler. Transformations operate on ordinary persistent data."
  (:refer-clojure :exclude [run!])
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [pattern.llvm.graph :as graph]
            [pattern.llvm.support :as support])
  (:import [java.io PushbackReader]
           [java.nio.file Files Path]
           [java.util.concurrent TimeUnit]))

(defn- remove-tree! [^Path directory]
  (with-open [paths (Files/walk directory (make-array java.nio.file.FileVisitOption 0))]
    (doseq [path (reverse (sort-by #(.getNameCount ^Path %) (iterator-seq (.iterator paths))))]
      (Files/deleteIfExists ^Path path))))

(defn- with-directory [f]
  (let [directory (Files/createTempDirectory "pattern-llvm-" (make-array java.nio.file.attribute.FileAttribute 0))]
    (try (f (.toFile directory)) (finally (remove-tree! directory)))))

(defn- run!
  [command directory {:keys [timeout-ms max-output-bytes]
                      :or {timeout-ms 30000 max-output-bytes 134217728} :as options}]
  (when-not (and (map? options)
                 (every? #{:timeout-ms :max-output-bytes} (keys options))
                 (integer? timeout-ms) (<= 1 timeout-ms 86400000)
                 (integer? max-output-bytes) (<= 1 max-output-bytes Long/MAX_VALUE))
    (throw (ex-info "Timeout must be 1..86400000ms; output limit positive signed64; unknown options rejected" {})))
  (let [budget (long (* 1000000 timeout-ms)) started (System/nanoTime)
        output (io/file directory "stdout") error (io/file directory "stderr")
        check-size! #(when (> (+' (.length output) (.length error)) max-output-bytes)
                       (throw (ex-info "LLVM bridge output limit exceeded" {:limit max-output-bytes})))
        process (-> (ProcessBuilder. ^java.util.List (mapv str command))
                    (.redirectOutput output) (.redirectError error) (.start))]
    (try
      (.close (.getOutputStream process))
      (loop []
        (check-size!)
        (when-not (.waitFor process 25 TimeUnit/MILLISECONDS)
          (when (> (- (System/nanoTime) started) budget)
            (throw (ex-info "LLVM bridge timed out" {:timeout-ms timeout-ms})))
          (recur)))
      (check-size!)
      (when-not (zero? (.exitValue process))
        (let [buffer (char-array 4096)
              diagnostic (with-open [reader (io/reader error :encoding "UTF-8")]
                           (let [n (.read reader buffer)]
                             (if (neg? n) "" (String. buffer 0 n))))]
          (throw (ex-info "LLVM bridge rejected operation"
                          {:exit-code (.exitValue process) :diagnostic diagnostic}))))
      output
      (finally
        (when (.isAlive process)
          (.destroyForcibly process)
          (.waitFor process 5 TimeUnit/SECONDS))))))

(defn- read-result [output]
  (with-open [reader (PushbackReader. (io/reader output :encoding "UTF-8") 64)]
    (let [value (json/read reader :key-fn keyword)]
      (when-not (= ::eof (json/read reader :eof-error? false :eof-value ::eof))
        (throw (ex-info "Trailing JSON after LLVM bridge result" {})))
      value)))

(defn capabilities
  "Query and validate the selected executable's versioned support contract.
   This runs no input module and loads no native library into the JVM."
  ([bridge] (capabilities bridge {}))
  ([bridge options]
   (with-directory
     (fn [directory]
       (support/validate-capabilities! (read-result (run! [bridge "capabilities"] directory options)))))))

(defn import-file
  "Import verified .ll or .bc through an explicitly selected bridge executable.
   No input code is executed. Return a persistent snapshot with versioned data.
   Process output defaults to 128MiB and 30s; options can raise these explicitly."
  ([bridge path] (import-file bridge path {}))
  ([bridge path options]
   (with-directory
     (fn [directory]
       (graph/from-wire (read-result (run! [bridge "import" path] directory options)))))))

(defn export-file!
  "Apply the recorded edits to the complete original assembly in a fresh native
   context, verify, and exclusively create PATH. Other LLVM fields are preserved
   by the baseline capsule; arbitrary data-to-module reconstruction is not provided.
   No destination file is overwritten. LLVM validity is not semantic equivalence."
  ([bridge snapshot path] (export-file! bridge snapshot path {}))
  ([bridge snapshot path options]
   (let [request (graph/export-request snapshot)]
     (with-directory
       (fn [directory]
         (let [input (io/file directory "baseline.ll") patch (io/file directory "patch.json")]
           (spit input (get-in snapshot [:origin :assembly]) :encoding "UTF-8")
           (with-open [writer (io/writer patch :encoding "UTF-8")]
             (json/write request writer))
           (run! [bridge "rewrite" input patch path] directory options)
           {:path (str path) :snapshot-id (:id snapshot) :edits (count (:edits snapshot))
            :llvm-verified true :semantic-equivalence-checked false}))))))
