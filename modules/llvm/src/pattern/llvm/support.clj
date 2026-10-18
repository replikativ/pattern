(ns pattern.llvm.support
  "Executable support queries for one closed LLVM inspection/edit profile.
   Coverage is a set of facets, not a claim of semantic completeness."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def profile-id "llvm21-persistent-ir/2")
(def schemas
  {:wire-format "pattern.llvm/2" :graph-format "pattern.llvm.graph/2"
   :edits-format "pattern.llvm.edits/2" :snapshot-format "pattern.llvm.snapshot/2"})

(def ^:private reviewed-profile
  ;; An explicit compatibility contract, independent of the executable selected
  ;; by the caller. New native facets must update this reviewed manifest too.
  (edn/read-string {:readers {} :default (fn [tag _] (throw (ex-info "Unexpected profile tag" {:tag tag})))}
                   (slurp (io/resource "pattern/llvm/profile.edn") :encoding "UTF-8")))

(defn- fail! [message data]
  (throw (ex-info message (assoc data :type :pattern.llvm/unsupported-support))))
(defn- check! [condition message data]
  (when-not condition (fail! message data)))

(defn validate-capabilities!
  "Validate the versioned native support declaration. The caller chooses a trusted
   bridge executable; a digest is not authentication of arbitrary host code."
  [capabilities]
  (check! (and (map? capabilities)
                (= "pattern.llvm.capabilities/2" (:format capabilities))
                (= profile-id (:profile-id capabilities))
                (every? (fn [[key value]] (= value (get capabilities key))) schemas)
                (string? (:llvm-version capabilities))
                (.startsWith ^String (:llvm-version capabilities) "21."))
          "Unknown LLVM support profile or protocol" {:profile (:profile-id capabilities)})
  (let [opcodes (:opcodes capabilities)]
    (check! (and (vector? opcodes) (seq opcodes)) "Missing opcode support inventory" {})
    (doseq [row opcodes]
      (check! (and (string? (:opcode row)) (integer? (:id row)) (pos? (:id row))
                    (string? (:family row))
                    (contains? #{"structural" "operation-fields" "unsupported"} (:projection row))
                    (contains? #{true false} (:serializable row))
                    (vector? (:fields row)) (every? string? (:fields row))
                    (vector? (:conditional-fields row)) (every? string? (:conditional-fields row))
                    (contains? #{"none" "integer-binop" "return-value"} (:operand-edit-policy row)))
              "Malformed opcode support row" {:opcode (:opcode row)})
      (when-not (:serializable row)
        (check! (and (= "unsupported" (:projection row)) (= "none" (:operand-edit-policy row)))
                "Reserved opcode advertised as supported" {:opcode (:opcode row)})))
    (check! (= (count opcodes) (count (set (map :id opcodes))) (count (set (map :opcode opcodes))))
            "Duplicate opcode identity in support inventory" {})
    (check! (= (vec (range 1 (inc (count opcodes)))) (vec (sort (map :id opcodes))))
            "Opcode inventory has an unclassified enum slot" {}))
  (check! (= reviewed-profile (dissoc capabilities :llvm-version))
          "Native capabilities differ from the reviewed support profile" {})
  capabilities)

(defn capabilities [snapshot]
  (get-in snapshot [:coverage :capabilities]))

(defn validate-coverage! [coverage llvm-version]
  (let [capabilities (validate-capabilities! (:capabilities coverage))]
    (check! (and (true? (:assembly-capsule coverage))
                  (= llvm-version (:llvm-version capabilities))
                  (false? (:typed-metadata coverage)))
            "Coverage and LLVM support profile disagree" {:llvm-version llvm-version})
    coverage))

(defn opcode-support [snapshot opcode]
  (first (filter #(= opcode (:opcode %)) (:opcodes (capabilities snapshot)))))

(defn node-support
  "Return the selected node's declared projection and actual editable slots.
   Conditions such as same type/dominance still apply to any proposed edit."
  [snapshot id]
  (let [node (get (:nodes snapshot) id)
        row (when (= "instruction" (:kind node)) (opcode-support snapshot (:opcode node)))
        policy (:operand-edit-policy row)
        editable? (and (:serializable row)
                        (or (= "integer-binop" policy)
                            (and (= "return-value" policy) (= 1 (count (:operands node))))))]
    (check! node "Unknown node ID" {:node id})
    {:node id :kind (:kind node) :opcode (:opcode node) :capability row
     :operand-edit-policy (or policy "none")
     :editable-operand-slots (if editable? (vec (range (count (:operands node)))) [])
     :metadata-inventory-known (vector? (:metadata-attachments node))
     :metadata-attachments (count (:metadata-attachments node))
     :debug-records-known (vector? (:debug-records node))
     :debug-record-count (count (:debug-records node))
     :typed-metadata false}))

(defn require-support!
  "Check requirements for EXPLICIT selected node IDs; return the same snapshot.
   :operation is :inspect (default) or :set-operand. :fields selects actual node
   fields; :metadata/:debug-records may be :preserved (default) or :none.
   This checks data availability/edit policy, never rewrite equivalence."
  [snapshot {:keys [nodes operation fields metadata debug-records]
             :or {operation :inspect fields [] metadata :preserved debug-records :preserved}
             :as requirements}]
  (check! (and (map? requirements)
                (every? #{:nodes :operation :fields :metadata :debug-records} (keys requirements))
                (vector? nodes) (coll? fields)
                (every? #(or (keyword? %) (string? %)) fields)
                (contains? #{:inspect :set-operand} operation)
                (contains? #{:preserved :none} metadata)
                (contains? #{:preserved :none} debug-records))
          "Invalid support requirements" {})
  (validate-coverage! (:coverage snapshot) (:llvm-version snapshot))
  (doseq [id nodes]
    (let [node (get (:nodes snapshot) id) report (node-support snapshot id)
          fields (mapv #(if (= "ir" (name %)) :original-ir (keyword (name %))) fields)
          missing (filterv #(not (contains? node %)) fields)]
      (check! (empty? missing) "Required structured fields are unavailable"
              {:node id :missing-fields missing})
      (when (= :set-operand operation)
        (check! (seq (:editable-operand-slots report)) "Opcode is not editable in this profile"
                {:node id :opcode (:opcode node)}))
      (when (= :none metadata)
        (check! (and (:metadata-inventory-known report) (zero? (:metadata-attachments report)))
                "Rule requires an explicit empty metadata inventory" {:node id}))
      (when (= :none debug-records)
        (check! (and (:debug-records-known report) (zero? (:debug-record-count report)))
                "Rule requires an explicit empty debug-record inventory" {:node id}))))
  snapshot)

(defn validate-instructions!
  "Check encountered opcodes and their declared unconditional projection fields.
   Conditional fields are queried per node, never assumed present from the opcode."
  [coverage nodes]
  (let [inventory (into {} (map (juxt :opcode identity)) (get-in coverage [:capabilities :opcodes]))]
    (doseq [[id node] nodes :when (= "instruction" (:kind node))]
      (let [row (get inventory (:opcode node))
            missing (filterv #(not (or (contains? node (keyword %))
                                       (and (= "sync-scope-name" %)
                                            (contains? node :sync-scope-name-bytes-hex))))
                             (concat (get-in coverage [:capabilities :common-instruction-fields])
                                     (:fields row)))]
        (check! (and row (:serializable row)) "Unclassified or reserved input opcode"
                {:node id :opcode (:opcode node)})
        (check! (empty? missing) "Bridge omitted declared operation fields"
                {:node id :opcode (:opcode node) :missing-fields missing})
        (check! (every? #(vector? (get node %)) [:successors :metadata-attachments :debug-records])
                "Malformed instruction edge or opaque inventory" {:node id}))))
  nodes)

(defn support-report
  "Summarize declared coverage separately from opcodes encountered in this image."
  [snapshot]
  (let [capabilities (validate-capabilities! (capabilities snapshot))
        instructions (filter #(= "instruction" (:kind %)) (vals (:nodes snapshot)))
        counts (frequencies (map :opcode instructions))]
    {:profile-id (:profile-id capabilities) :llvm-version (:llvm-version capabilities)
     :declared-opcode-slots (count (:opcodes capabilities))
     :declared-serializable-opcodes (count (filter :serializable (:opcodes capabilities)))
     :encountered-opcodes (into (sorted-map) counts)
     :instructions (count instructions)
     :editable-instructions (count (filter #(seq (:editable-operand-slots (node-support snapshot (:id %)))) instructions))
     :metadata-projection :capsule-and-attachment-inventory
     :debug-record-projection :capsule-and-opaque-text-inventory
     :semantic-equivalence-checked false}))
