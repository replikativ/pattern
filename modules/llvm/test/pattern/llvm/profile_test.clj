(ns pattern.llvm.profile-test
  "All-65 opcode encounter coverage plus representative native /2 projection
   and edit acceptance. Encounter coverage is not exhaustive semantic coverage."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.set :as set]
            [clojure.test :refer :all]
            [pattern :as pattern]
            [pattern.llvm :as llvm]
            [pattern.llvm.graph :as graph]
            [pattern.llvm.integration-test :as integration]
            [pattern.llvm.support :as support]))

(def fixture "modules/llvm/test/fixtures/profile.ll")
(def debug-fixture "modules/llvm/test/fixtures/profile-debug.ll")
(def opcode-fixture "modules/llvm/test/fixtures/opcodes.ll")
(defonce imported (delay (llvm/import-file integration/bridge fixture)))
(defonce debug-imported (delay (llvm/import-file integration/bridge debug-fixture)))
(defonce opcodes-imported (delay (llvm/import-file integration/bridge opcode-fixture)))

(defn named [snapshot name]
  (first (filter #(= name (:name %)) (vals (:nodes snapshot)))))

(defn opcode [snapshot name]
  (first (filter #(= name (:opcode %)) (vals (:nodes snapshot)))))

(defn initializer [snapshot name]
  (graph/node snapshot (:initializer (named snapshot name))))

(deftest native-profile-inventory-is-explicit-and-shared
  (let [snapshot @imported
        capabilities (llvm/capabilities integration/bridge)
        rows (:opcodes capabilities)
        reserved (filterv (comp not :serializable) rows)
        report (support/support-report snapshot)]
    (is (= capabilities (support/capabilities snapshot)))
    (is (= "llvm21-persistent-ir/2" (:profile-id capabilities)))
    (is (= "pattern.llvm.snapshot/2" (:format snapshot)))
    (is (= 67 (count rows)))
    (is (= 65 (count (filter :serializable rows))))
    (is (= #{"userop1" "userop2"} (set (map :opcode reserved))))
    (is (every? #(and (= "unsupported" (:projection %))
                     (= "none" (:operand-edit-policy %))) reserved))
    (is (= (vec (range 1 68)) (vec (sort (map :id rows)))))
    (is (= 67 (:declared-opcode-slots report)))
    (is (= 65 (:declared-serializable-opcodes report)))
    (is (false? (:semantic-equivalence-checked report)))
    (is (every? #(get-in (support/node-support snapshot (:id %)) [:capability :serializable])
                (filter #(= "instruction" (:kind %)) (vals (:nodes snapshot)))))))

(deftest nondefault-poison-fastmath-and-operation-fields-survive
  (let [snapshot @imported
        node #(named snapshot %)
        gep (node "g")
        atomic (node "cx")
        fence (opcode snapshot "fence")]
    (is (true? (:disjoint (node "disjoint"))))
    (is (true? (:same-sign (node "same"))))
    (is (= "ult" (:predicate (node "same"))))
    (is (true? (:nonneg (node "z"))))
    (is (true? (:nonneg (node "uf"))))
    (is (= [true true] ((juxt :nuw :nsw) (node "t"))))
    (is (= [true true] ((juxt :nuw :nsw) (node "sum"))))
    (is (true? (:exact (node "exact"))))
    (is (= #{:reassoc :nnan :ninf :nsz :arcp :contract :afn}
           (set (keys (:fast-math (node "fa"))))))
    (is (every? true? (vals (:fast-math (node "fa")))))
    (is (= [true true true] ((juxt :inbounds :nusw :nuw) gep)))
    (is (= [1 2] (:index-slots gep)))
    (is (= (:indices gep) (mapv #(nth (:operands gep) %) (:index-slots gep))))
    (is (= (:id (node "al")) (nth (:operands gep) (:pointer-slot gep))))
    (is (= [0] (:indices (node "pair"))))
    (is (= [0] (:indices (node "extracted"))))
    (is (= [1 0] (:mask (node "shuffled"))))
    (is (= [true true 4] ((juxt :weak :volatile :alignment) atomic)))
    (is (= ["acq_rel" "acquire" "singlethread"]
           ((juxt :success-ordering-name :failure-ordering-name :sync-scope-name) atomic)))
    (is (= "add" (:operation-name (node "rmw"))))
    (is (= "acq_rel" (:ordering-name (node "rmw"))))
    (is (= [true "acquire" 4] ((juxt :volatile :ordering-name :alignment) (node "loaded"))))
    (is (= "ff" (:sync-scope-name-bytes-hex fence)))
    (is (not (contains? fence :sync-scope-name)))
    (is (= "seq_cst" (:ordering-name fence)))
    (is (some #(and (= "target-ext" (:kind %)) (= "spirv.Event" (:name %)))
              (vals (:types snapshot))))
    (is (some #(and (= "vector" (:kind %)) (true? (:scalable %)) (= 4 (:count %)))
              (vals (:types snapshot))))))

(deftest call-roles-use-actual-native-slots-and-attributes
  (let [snapshot @imported
        call (named snapshot "call")
        operands (:operands call)
        bundle (first (:operand-bundles call))
        attributes (mapcat :attributes (:attribute-records (named snapshot "attrs")))
        range-attribute (first (filter #(= "range" (:kind %)) attributes))]
    (is (= (:id (named snapshot "callee")) (:callee call)))
    (is (= (:callee call) (nth operands (:callee-slot call))))
    (is (= (:arguments call) (mapv #(nth operands %) (:argument-slots call))))
    (is (= "deopt" (:tag bundle)))
    (is (= (:inputs bundle) (mapv #(nth operands %) (:input-slots bundle))))
    (is (= (:arguments call) (:inputs bundle)) "same value occupies distinct argument and bundle slots")
    (is (empty? (set/intersection (set (:argument-slots call)) (set (:input-slots bundle)))))
    (is (= "tail" (:tail-kind call)))
    (is (contains? (:types snapshot) (:function-type call)))
    (is (= #{"enum" "int" "range" "type" "string"} (set (map :kind attributes))))
    (is (= {:bits 32 :lower-bits-hex "0" :upper-bits-hex "A" :full false :empty false}
           (:range range-attribute)))
    (is (some #(and (= "type" (:kind %)) (= "byval" (:name %))
                    (contains? (:types snapshot) (:type %))) attributes))
    (is (some #(and (= "string" (:kind %)) (= "target-cpu" (:name %))
                    (= "generic" (:value %))) attributes))))

(deftest compact-constants-preserve-element-bits-without-expansion
  (let [snapshot @imported
        bytes (initializer snapshot "bytes")
        words (initializer snapshot "words")
        doubles (initializer snapshot "doubles")
        vectors (initializer snapshot "vectors")]
    (is (= ["constant-data-array" 4 8 "4100ff7a"]
           ((juxt :kind :count :element-bits :data-hex) bytes)))
    (is (= [2 16 "3412ffff"] ((juxt :count :element-bits :data-hex) words)))
    (is (= [2 64 "0000000000000080420000000000f87f"]
           ((juxt :count :element-bits :data-hex) doubles)))
    (is (= ["constant-data-vector" 2 32 "0700000009000000"]
           ((juxt :kind :count :element-bits :data-hex) vectors)))
    (is (every? #(= "packed-little-endian-element-bits" (:encoding %)) [bytes words doubles vectors]))
    (is (every? #(empty? (:operands %)) [bytes words doubles vectors]))
    (is (= ["payloads" 8 true] ((juxt :section :alignment :constant) (named snapshot "bytes"))))
    (is (true? (:thread-local (named snapshot "words"))))
    (is (= "alias" (:kind (named snapshot "alias"))))
    (is (= (:id (named snapshot "worker")) (:aliasee (named snapshot "alias"))))
    (is (= (:id (named snapshot "resolver")) (:resolver (named snapshot "dispatch_fn"))))))

(deftest eh-metadata-and-real-debug-record-inventories
  (let [snapshot @imported
        landing (named snapshot "lp")
        switch (named snapshot "cs")
        clean (named snapshot "clean")
        sum (named snapshot "sum")
        debug @debug-imported
        ret (opcode debug "ret")]
    (is (true? (:cleanup landing)))
    (is (= ["catch"] (mapv :kind (:clauses landing))))
    (is (true? (:unwinds-to-caller switch)))
    (is (= 1 (count (:handlers switch))))
    (is (= (:id switch) (:parent-pad (named snapshot "cp"))))
    (is (= [] (:arguments clean)))
    (is (= (:id clean) (:cleanup-pad (opcode snapshot "cleanupret"))))
    (is (= ["annotation"] (mapv :kind-name (:metadata-attachments sum))))
    (is (= ["named"] (mapv :name (get-in snapshot [:module :named-metadata]))))
    (is (= [] (:debug-records sum)))
    (is (false? (get-in snapshot [:coverage :typed-metadata])))
    (is (= 1 (count (:debug-records ret))))
    (is (re-find #"#dbg_value" (first (:debug-records ret))))
    (is (= ["dbg"] (mapv :kind-name (:metadata-attachments ret))))
    (is (= 1 (:debug-record-count (support/node-support debug (:id ret)))))))

(deftest representative-ll-bitcode-and-no-edit-export-parity
  (doseq [[path snapshot] [[fixture @imported] [debug-fixture @debug-imported]
                         [opcode-fixture @opcodes-imported]]]
    (integration/in-directory
      (fn [directory]
        (let [bitcode (str (io/file directory "profile.bc"))
              output (str (io/file directory "profile.ll"))]
          (is (zero? (:exit (shell/sh (integration/tool-path "llvm-as") path "-o" bitcode))))
          (let [from-bitcode (llvm/import-file integration/bridge bitcode)]
            (is (= (:nodes snapshot) (:nodes from-bitcode)))
            (is (= (:types snapshot) (:types from-bitcode)))
            (is (= (:module snapshot) (:module from-bitcode))))
          (is (= 0 (:edits (llvm/export-file! integration/bridge snapshot output))))
          (let [restored (llvm/import-file integration/bridge output)]
            (is (= (:nodes snapshot) (:nodes restored)))
            (is (= (:types snapshot) (:types restored)))
            (is (= (:module snapshot) (:module restored)))))))))

(deftest actual-pattern-rule-rewrite-exports-with-flags-and-metadata
  (integration/in-directory
    (fn [directory]
      (let [snapshot @imported
            sum (named snapshot "sum")
            swap (pattern/rule '(?:as node {:opcode "add" :operands [?left ?right]})
                   (assoc node :operands [right left]))
            child (graph/rewrite-node snapshot (:id sum) swap)
            changed (graph/node child (:id sum))
            output (str (io/file directory "pattern-rule.ll"))]
        (is (= (vec (reverse (:operands sum))) (:operands changed)))
        (is (= sum (graph/node snapshot (:id sum))))
        (is (= (dissoc sum :operands) (dissoc changed :operands)))
        (is (= 2 (count (:edits child))))
        (is (= 2 (:edits (llvm/export-file! integration/bridge child output))))
        (let [restored (llvm/import-file integration/bridge output)
              result (named restored "sum")]
          (is (= ["b" "a"] (mapv #(get-in restored [:nodes % :name]) (:operands result))))
          (is (= [true true] ((juxt :nuw :nsw) result)))
          (is (= (:metadata-attachments sum) (:metadata-attachments result))))))))

(deftest combined-corpus-encounters-every-serializable-opcode
  (let [snapshots [@integration/imported @imported @debug-imported @opcodes-imported]
        expected (->> (support/capabilities @imported) :opcodes
                      (filter :serializable) (map :opcode) set)
        encountered (->> snapshots (mapcat (comp vals :nodes))
                         (filter #(= "instruction" (:kind %))) (map :opcode) set)]
    (is (= 65 (count expected)))
    (is (= #{} (set/difference expected encountered)))
    (is (= expected encountered))))

(defn function-instructions [snapshot function-name]
  (let [blocks (set (:blocks (named snapshot function-name)))]
    (filter #(and (= "instruction" (:kind %)) (contains? blocks (:parent %)))
            (vals (:nodes snapshot)))))

(deftest all-integer-edit-policies-and-return-export-in-one-transaction
  ;; Swapping division, remainder and shift operands is deliberately NOT claimed
  ;; equivalent. This test establishes native validity and exact patch transport.
  (integration/in-directory
    (fn [directory]
      (let [snapshot @opcodes-imported
            instructions (function-instructions snapshot "integer_ops")
            binaries (filter #(= "integer-binop"
                                 (:operand-edit-policy (support/node-support snapshot (:id %))))
                             instructions)
            ret (first (filter #(= "ret" (:opcode %)) instructions))
            argument (first (:arguments (named snapshot "integer_ops")))
            swapped (reduce (fn [world instruction]
                              (graph/rewrite-node world (:id instruction)
                                #(update % :operands (comp vec reverse))))
                            snapshot (sort-by :id binaries))
            child (graph/rewrite-node swapped (:id ret) #(assoc % :operands [argument]))
            output (str (io/file directory "all-integer-edits.ll"))]
        (is (= #{"add" "sub" "mul" "udiv" "sdiv" "urem" "srem"
                 "and" "or" "xor" "shl" "lshr" "ashr"}
               (set (map :opcode binaries))))
        (is (= 13 (count binaries)))
        (is (= 27 (count (:edits child))))
        (is (= 27 (:edits (llvm/export-file! integration/bridge child output))))
        (doseq [instruction binaries]
          (is (= (vec (reverse (:operands instruction)))
                 (:operands (graph/node child (:id instruction))))))
        (is (= (:nodes snapshot) (get-in child [:origin :base :nodes])))
        (let [restored (llvm/import-file integration/bridge output)
              actual (function-instructions restored "integer_ops")
              by-name (into {} (map (juxt :name identity)) actual)]
          (doseq [instruction binaries]
            (is (= ["b" "a"]
                   (mapv #(get-in restored [:nodes % :name])
                         (:operands (get by-name (:name instruction)))))))
          (is (= ["a"]
                 (mapv #(get-in restored [:nodes % :name])
                       (:operands (first (filter #(= "ret" (:opcode %)) actual)))))))))))
