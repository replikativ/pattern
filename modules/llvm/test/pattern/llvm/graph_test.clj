(ns pattern.llvm.graph-test
  (:require [clojure.test :refer :all]
            [pattern.llvm.graph :as graph]))

(defn seal [wire]
  (assoc wire :graph-sha256
         (graph/baseline-digest (:llvm-version wire) (:coverage wire)
                                (dissoc (:module wire) :assembly :identifier)
                                (:nodes wire) (:types wire))))

(def wire
  (seal
  {:format "pattern.llvm/1" :llvm-version "21.1.2"
   :coverage {:assembly-capsule true :typed-metadata false}
   :module {:assembly "; fake unit-test baseline\n" :functions [1]}
   :types [{:id 1 :kind "integer" :elements []}]
   :nodes [{:id 1 :kind "instruction" :type 1 :opcode "add" :operands [2 2] :ir "baseline"}
           {:id 2 :kind "argument" :type 1 :operands [] :ir "argument"}
           {:id 3 :kind "constant-int" :type 1 :operands [] :ir "constant"}]}))

(deftest persistent-use-site-transactions
  (let [base (graph/from-wire wire)
        child (graph/set-operand base 1 0 3)
        sibling (graph/set-operand base 1 1 3)
        both (graph/replace-uses base 2 3)]
    (is (= #{[1 0] [1 1]} (graph/uses base 2)))
    (is (= #{[1 1]} (graph/uses child 2)))
    (is (= #{[1 0]} (graph/uses sibling 2)))
    (is (= [3 3] (get-in both [:nodes 1 :operands])))
    (is (= #{} (graph/uses both 2)))
    (is (identical? (:types base) (:types child)))
    (is (identical? (graph/node base 2) (graph/node child 2)))
    (is (= [2 2] (get-in base [:nodes 1 :operands])))
    (is (= 2 (count (:edits (graph/export-request both)))))
    (is (identical? base (graph/set-operand base 1 0 2)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"index" (graph/set-operand base 1 -1 3)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"different type" (graph/set-operand base 1 0 999)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unsupported graph mutation"
                         (graph/export-request (assoc-in base [:nodes 1 :opcode] "sub"))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Stale operand edit"
                         (graph/export-request (assoc-in child [:edits 0 :from] 3))))))

(deftest duplicate-and-dangling-ids-rejected
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Duplicate"
                       (graph/from-wire (seal (update wire :nodes conj (first (:nodes wire)))))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Dangling operand"
                       (graph/from-wire (seal (assoc-in wire [:nodes 0 :operands] [999]))))))

(deftest profile-and-baseline-tampering-rejected
  (let [base (graph/from-wire wire)
        changed (-> base (assoc-in [:nodes 1 :nsw] true)
                    (assoc-in [:origin :base :nodes 1 :nsw] true))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Origin graph changed"
                         (graph/export-request changed)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"profile changed"
                         (graph/export-request (assoc base :llvm-version "22.0"))))))
