(ns pattern.llvm.bifurcan-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [pattern.llvm :as llvm]
            [pattern.llvm.bifurcan :as bifurcan]
            [pattern.llvm.graph :as graph]
            [pattern.llvm.integration-test :as integration]))

(deftest integer-table-behaves-as-a-persistent-clojure-map
  (let [table (bifurcan/node-map {1 nil 2 :two}) child (assoc table 2 :changed)]
    (is (= {1 nil 2 :two} table))
    (is (= table {1 nil 2 :two}))
    (is (= (hash {1 nil 2 :two}) (hash table)))
    (is (contains? table 1))
    (is (= :missing (get table 3 :missing)))
    (is (= :changed (get child 2)))
    (is (= :two (get table 2)))
    (is (= {2 :two} (dissoc table 1)))
    (is (nil? (get table 9223372036854775809N)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"signed64"
                         (assoc table 9223372036854775809N :bad)))
    (is (= :stopped (reduce-kv (fn [_ _ _] (reduced :stopped)) nil table)))
    (is (= {:tag true} (meta (empty (with-meta table {:tag true})))))))

(deftest bifurcan-snapshots-use-same-native-export-contract
  (integration/in-directory
    (fn [directory]
      (let [original @integration/imported base (bifurcan/with-backend original)
            twice (integration/named base "twice") identity (integration/named base "identity")
            answer (integration/named base "answer")
            child (graph/replace-uses base (:id identity) (:id answer))
            output (str (io/file directory "bifurcan.ll"))]
        (is (= (:nodes original) (:nodes base)))
        (is (= (:types original) (:types base)))
        (is (= #{[(:id twice) 0] [(:id twice) 1]} (graph/uses base (:id identity))))
        (is (= [(:id identity) (:id identity)] (:operands (integration/named base "twice"))))
        (is (identical? (integration/named base "loop") (integration/named child "loop")))
        (is (= 2 (:edits (llvm/export-file! integration/bridge child output))))
        (let [project (fn [snapshot]
                        (into {} (map (fn [[id node]] [id (dissoc node :original-ir)])) (:nodes snapshot)))]
          (is (= (project (llvm/import-file integration/bridge output)) (project child))))))))
