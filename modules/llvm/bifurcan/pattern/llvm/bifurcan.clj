(ns pattern.llvm.bifurcan
  "Optional persistent integer-keyed tables. Node payloads stay ordinary Clojure
   maps for Pattern. Linear builders are private and never survive publication."
  (:import [io.lacuna.bifurcan IntMap]
           [clojure.lang APersistentMap IObj IKVReduce MapEntry]))

(defn- id? [id] (and (integer? id) (<= 1 id Long/MAX_VALUE)))
(declare wrap)

(defn- wrap [^IntMap table metadata]
  (assert (not (.isLinear table)))
  (proxy [APersistentMap IObj IKVReduce] []
    (count [] (Math/toIntExact (.size table)))
    (containsKey [key] (and (id? key) (.contains table (long key))))
    (entryAt [key]
      (when (and (id? key) (.contains table (long key)))
        (MapEntry/create key (.get table (long key) nil))))
    (valAt
      ([key] (when (id? key) (.get table (long key) nil)))
      ([key not-found] (if (id? key) (.get table (long key) not-found) not-found)))
    (assoc [key value]
      (when-not (id? key) (throw (ex-info "Expected positive signed64 ID" {:id key})))
      (wrap (.put table (long key) value) metadata))
    (assocEx [key value]
      (when (and (id? key) (.contains table (long key)))
        (throw (IllegalArgumentException. "Key already present")))
      (.assoc ^clojure.lang.IPersistentMap this key value))
    (without [key]
      (if (and (id? key) (.contains table (long key)))
        (wrap (.remove table (long key)) metadata) this))
    (seq []
      (seq (map #(MapEntry/create (.key ^io.lacuna.bifurcan.IEntry %)
                                  (.value ^io.lacuna.bifurcan.IEntry %))
                (iterator-seq (.iterator table)))))
    (iterator [] (clojure.lang.SeqIterator. (seq this)))
    (empty [] (wrap (IntMap.) metadata))
    (meta [] metadata)
    (withMeta [new-metadata] (wrap table new-metadata))
    (kvreduce [f initial]
      (loop [entries (.iterator table) result initial]
        (if (.hasNext entries)
          (let [entry ^io.lacuna.bifurcan.IEntry (.next entries)
                result (f result (.key entry) (.value entry))]
            (if (reduced? result) @result (recur entries result)))
          result)))))

(defn node-map
  "Build a persistent IntMap behind normal Clojure map interfaces. Mutation is
   confined to this unshared builder; every returned map is forked/immutable."
  [entries]
  (let [builder (reduce (fn [^IntMap table [id value]]
                          (when-not (id? id)
                            (throw (ex-info "Expected positive signed64 ID" {:id id})))
                          (.put table (long id) value))
                        (.linear (IntMap.)) entries)]
    (wrap (.forked ^IntMap builder) nil)))

(defn with-backend
  "Convert snapshot tables once. All graph operations and export validation keep
   the same API. No DirectedGraph collapses duplicate operand or CFG edges."
  [snapshot]
  (let [nodes (node-map (:nodes snapshot)) types (node-map (:types snapshot))
        base-nodes (get-in snapshot [:origin :base :nodes])
        base-types (get-in snapshot [:origin :base :types])]
    (-> snapshot
        (assoc :nodes nodes :types types
               :uses (node-map (:uses snapshot)) :predecessors (node-map (:predecessors snapshot)))
        (assoc-in [:origin :base :nodes]
                  (if (identical? base-nodes (:nodes snapshot)) nodes (node-map base-nodes)))
        (assoc-in [:origin :base :types]
                  (if (identical? base-types (:types snapshot)) types (node-map base-types))))))
