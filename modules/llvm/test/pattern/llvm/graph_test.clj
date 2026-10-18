(ns pattern.llvm.graph-test
  (:require [clojure.test :refer :all]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [pattern.llvm.graph :as graph]
            [pattern.llvm.support :as support]))

(def capabilities
  (assoc (edn/read-string (slurp (io/resource "pattern/llvm/profile.edn"))) :llvm-version "21.1.2"))

(defn seal [wire]
  (assoc wire :graph-sha256
         (graph/baseline-digest (:llvm-version wire) (:coverage wire)
                                (dissoc (:module wire) :assembly :identifier)
                                (:nodes wire) (:types wire))))

(def wire
  (seal
  {:format "pattern.llvm/2" :llvm-version "21.1.2"
   :coverage {:assembly-capsule true :typed-metadata false :capabilities capabilities}
   :module {:assembly "; fake unit-test baseline\n" :functions [1]}
   :types [{:id 1 :kind "integer" :elements []}]
   :nodes [{:id 1 :kind "instruction" :type 1 :opcode "add" :operands [2 2] :ir "baseline"
            :parent 1 :successors [] :metadata-attachments [] :debug-records [] :nuw false :nsw false}
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
    (is (= (:id base) (:parent both)))
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

(deftest closed-support-profile
  (is (= capabilities (support/validate-capabilities! capabilities)))
  (doseq [bad [(update capabilities :opcodes subvec 0 1)
               (assoc-in capabilities [:opcodes 0 :opcode] "unknown")
               (assoc-in capabilities [:opcodes 0 :operand-edit-policy] "integer-binop")]]
    (is (thrown? clojure.lang.ExceptionInfo (support/validate-capabilities! bad))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"wire format"
                       (graph/from-wire (assoc wire :format "pattern.llvm/1")))))

(deftest explicit-support-and-rule-transactions
  (let [base (graph/from-wire wire)
        child (graph/rewrite-node base 1 #(assoc % :operands [3 3]))]
    (is (identical? base (support/require-support! base {:nodes [1] :operation :set-operand
                                                       :fields [:nuw :nsw] :metadata :none :debug-records :none})))
    (is (= (:id base) (:parent child)))
    (is (= [3 3] (:operands (graph/node child 1))))
    (is (= 2 (count (:edits (graph/export-request child)))))
    (is (identical? base (graph/rewrite-node base 2 identity)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unsupported fields"
                         (graph/rewrite-node base 1 #(assoc % :operands [3 3] :nsw true))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"different type"
                         (graph/rewrite-node base 1 #(assoc % :operands [3 999]))))
    (is (= [2 2] (:operands (graph/node base 1))))
    (is (= [] (:edits base)))
    (is (thrown? clojure.lang.ExceptionInfo (support/require-support! base {:nodes [1] :fields [:callee]})))
    (is (thrown? clojure.lang.ExceptionInfo (support/require-support! base {:nodes [2] :metadata :none})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (support/require-support! (assoc-in base [:nodes 1 :metadata-attachments] nil)
                                           {:nodes [1] :metadata :none})))
    (is (thrown? clojure.lang.ExceptionInfo (support/require-support! base {:nodes [1] :unknown true})))))

(deftest mandatory-instruction-inventories-are-typed
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"omitted declared"
        (graph/from-wire (seal (update-in wire [:nodes 0]
                                #(-> % (dissoc :successors) (assoc :successors-bytes-hex "00")))))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Malformed instruction"
        (graph/from-wire (seal (assoc-in wire [:nodes 0 :debug-records] nil))))))
