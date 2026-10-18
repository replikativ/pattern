(ns pattern.llvm.graph
  "Persistent, normalized LLVM value/type graphs. IDs belong to one imported
   module lineage. Names are presentation; native pointers never escape."
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.util HexFormat UUID]))

(defn sha256 [^String text]
  (.formatHex (HexFormat/of)
              (.digest (MessageDigest/getInstance "SHA-256")
                       (.getBytes text StandardCharsets/UTF_8))))

(defn- require! [condition message data]
  (when-not condition (throw (ex-info message data))))

(defn- table [records label]
  (require! (vector? records) "Expected a vector of records" {:table label})
  (reduce (fn [result {:keys [id] :as record}]
            (require! (and (integer? id) (<= 1 id Long/MAX_VALUE) (not (contains? result id)))
                      "Duplicate or invalid entity ID" {:table label :id id})
            (assoc result id record))
          {} records))

(defn- operand-sites [nodes]
  (reduce-kv (fn [result user {:keys [operands]}]
               (reduce-kv (fn [result slot target]
                            (update result target (fnil conj #{}) [user slot]))
                          result operands))
             {} nodes))

(defn- canonical-json [value]
  ;; Match the native profile's LLVM JSON scalar escaping exactly: UTF-8 is
  ;; literal; backspace/formfeed use \u0008/\u000c, not the JS-oriented choices
  ;; of a general JSON writer. This profile has no floating-point JSON numbers.
  (let [out (StringBuilder.)]
    (letfn [(quoted [^String text]
              (.append out \" )
              (doseq [c text]
                (.append out ^String
                         (case c
                           \" "\\\"" \\ "\\\\" \newline "\\n" \return "\\r" \tab "\\t"
                           (if (< (int c) 32) (format "\\u%04x" (int c)) (str c)))))
              (.append out \" ))
            (items [values f]
              (doseq [[index value] (map-indexed vector values)]
                (when (pos? index) (.append out \,))
                (f value)))
            (emit [value]
              (cond
                (map? value) (do (.append out \{)
                                 (items (sort-by (comp name key) value)
                                        (fn [[key value]] (quoted (name key)) (.append out \:) (emit value)))
                                 (.append out \}))
                (sequential? value) (do (.append out \[) (items value emit) (.append out \]))
                (string? value) (quoted value)
                (nil? value) (.append out "null")
                (true? value) (.append out "true")
                (false? value) (.append out "false")
                (integer? value) (.append out (str value))
                :else (throw (ex-info "Value outside canonical graph JSON profile" {:value-type (type value)}))))]
      (emit value)
      (str out))))

(defn baseline-digest
  "Digest of native graph records, baseline module semantics, version and coverage.
   Object keys are sorted; integer values and ordered arrays stay exact."
  [llvm-version coverage module nodes types]
  (sha256 (canonical-json {:format "pattern.llvm.graph/1" :llvm-version llvm-version
                           :coverage coverage :module module
                           :nodes (vec (sort-by :id nodes))
                           :types (vec (sort-by :id types))})))

(defn- predecessors [nodes]
  (reduce-kv (fn [result instruction node]
               (reduce-kv (fn [result slot block]
                            (update result block (fnil conj #{}) [instruction slot]))
                          result (or (:successors node) [])))
             {} nodes))

(defn from-wire
  "Construct an immutable snapshot. Repeated operand slots and successor slots
   remain distinct, including loops. Text fields describe the imported baseline;
   subsequent edits are explicit data, not implicit textual substitutions."
  [{:keys [format llvm-version module nodes types coverage graph-sha256]}]
  (require! (= "pattern.llvm/1" format) "Unsupported LLVM wire format" {:format format})
  (require! (and (string? llvm-version) (.startsWith ^String llvm-version "21."))
            "This fidelity profile requires LLVM 21" {:llvm-version llvm-version})
  (require! (string? (:assembly module)) "Missing canonical module assembly" {})
  (require! (and (map? coverage) (true? (:assembly-capsule coverage))
                 (string? graph-sha256)) "Missing fidelity declaration or graph digest" {})
  (require! (= graph-sha256 (baseline-digest llvm-version coverage
                             (dissoc module :assembly :identifier) nodes types))
            "Native baseline graph digest mismatch" {})
  (let [types (table types :types)
        nodes (table (mapv #(-> % (assoc :original-ir (:ir %)) (dissoc :ir)) nodes) :nodes)]
    (doseq [[id node] nodes]
      (require! (and (contains? types (:type node)) (vector? (:operands node)))
                "Malformed value type or operand vector" {:id id})
      (doseq [target (:operands node)]
        (require! (contains? nodes target) "Dangling operand" {:user id :target target}))
      (doseq [target (:successors node)]
        (require! (= "block" (:kind (get nodes target)))
                  "Successor must reference a block" {:user id :target target})))
    (doseq [[id type] types child (:elements type)]
      (require! (contains? types child) "Dangling type component" {:type id :child child}))
    (let [assembly (:assembly module)
          base {:module (dissoc module :assembly :identifier) :nodes nodes :types types}
          indexes {:uses (operand-sites nodes) :predecessors (predecessors nodes)}]
      (merge base indexes
             {:format "pattern.llvm.snapshot/1" :id (str (UUID/randomUUID))
              :llvm-version llvm-version :edits []
              :coverage coverage
              :origin {:sha256 (sha256 assembly) :assembly assembly :base base
                       :identifier (:identifier module) :llvm-version llvm-version
                       :coverage coverage :graph-sha256 graph-sha256}}))))

(defn node [snapshot id] (get (:nodes snapshot) id))
(defn fork [snapshot]
  (assoc snapshot :id (str (UUID/randomUUID)) :parent (:id snapshot)))
(defn uses
  "Return #{[user-id operand-index]}; repeated occurrences are separate sites."
  [snapshot id] (get (:uses snapshot) id #{}))
(defn incoming-edges
  "Return #{[terminator-id successor-index]}; parallel CFG edges are distinct."
  [snapshot block] (get (:predecessors snapshot) block #{}))

(def editable-opcodes
  #{"add" "sub" "mul" "and" "or" "xor" "shl" "lshr" "ashr"
    "udiv" "sdiv" "urem" "srem" "ret"})

(defn set-operand
  "Return a new snapshot; do not mutate any prior snapshot. Only existing values
   of the same native type may be used. Native verification at export still must
   establish dominance and all other LLVM invariants. Validity is not equivalence."
  [snapshot user slot target]
  (let [instruction (node snapshot user) replacement (node snapshot target)
        operands (:operands instruction)]
    (require! (and (= "instruction" (:kind instruction))
                   (contains? editable-opcodes (:opcode instruction)))
              "Opcode is not editable in this profile" {:user user :opcode (:opcode instruction)})
    (require! (and (integer? slot) (<= 0 slot) (< slot (count operands)))
              "Operand index out of range" {:user user :slot slot})
    (let [old (nth operands slot) old-node (node snapshot old)]
      (require! (and replacement (= (:type replacement) (:type old-node)))
                "Replacement value is absent or has a different type"
                {:user user :slot slot :target target})
      (if (= old target)
        snapshot
        (let [site [user slot]
              prior-sites (disj (uses snapshot old) site)]
          (-> snapshot
              (assoc :id (str (UUID/randomUUID)) :parent (:id snapshot))
              (assoc-in [:nodes user :operands slot] target)
              (update :uses #(cond-> %
                               (seq prior-sites) (assoc old prior-sites)
                               (empty? prior-sites) (dissoc old)))
              (update-in [:uses target] (fnil conj #{}) site)
              (update :edits conj {:user user :slot slot :from old :to target})))))))

(defn replace-uses
  "Retarget all original operand sites in a private persistent transaction. Throws
   if any user is unsupported; callers keep their unchanged input on failure.
   This does not replace PHI predecessor blocks or metadata-only references."
  [snapshot old target]
  (reduce (fn [current [user slot]] (set-operand current user slot target))
          snapshot (sort (uses snapshot old))))

(defn export-request
  "Reject direct graph/type/module/derived-index mutations outside the admitted
   operand edit API. Return a source-bound patch, checked again by native LLVM."
  [snapshot]
  (let [{:keys [assembly sha256 base llvm-version coverage graph-sha256]} (:origin snapshot)
        _ (require! (and (= "pattern.llvm.snapshot/1" (:format snapshot))
                         (= llvm-version (:llvm-version snapshot))
                         (= coverage (:coverage snapshot))) "Snapshot profile changed" {})
        _ (require! (= sha256 (pattern.llvm.graph/sha256 assembly)) "Origin assembly changed" {})
        original-nodes (mapv #(-> % (assoc :ir (:original-ir %)) (dissoc :original-ir))
                            (vals (:nodes base)))
        _ (require! (every? #(not (contains? % :ir)) (vals (:nodes base)))
                    "Origin contains unrecorded display fields" {})
        _ (require! (= graph-sha256 (baseline-digest llvm-version coverage
                                     (:module base) original-nodes (vals (:types base))))
                    "Origin graph changed outside the edit protocol" {})
        initial (merge base {:uses (operand-sites (:nodes base))
                             :predecessors (predecessors (:nodes base)) :edits []})
        expected (reduce (fn [current {:keys [user slot from to]}]
                           (require! (= from (get-in current [:nodes user :operands slot]))
                                     "Stale operand edit" {:user user :slot slot})
                           (set-operand current user slot to))
                         initial (:edits snapshot))]
    (doseq [key [:module :nodes :types :uses :predecessors]]
      (require! (= (get expected key) (get snapshot key))
                "Unrecorded or unsupported graph mutation" {:field key}))
    {:format "pattern.llvm.edits/1" :source-sha256 sha256
     :llvm-version llvm-version :graph-sha256 graph-sha256 :edits (:edits snapshot)}))
