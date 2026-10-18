(ns pattern.llvm.benchmark
  "Bounded synthetic persistent-table comparison, not an LLVM or microbenchmark.
   Measures the optional Clojure map facade, including its proxy overhead."
  (:require [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [pattern.llvm.bifurcan :as bifurcan])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.util HexFormat]))

(def defaults
  {:nodes 10000 :forks 100 :updates-per-fork 10 :batch-updates 1000
   :lookup-count 100000 :rounds 3})

(defn- check! [condition message data]
  (when-not condition (throw (ex-info message data))))

(defn- options! [options]
  (check! (and (map? options) (every? (set (keys defaults)) (keys options)))
          "Unknown benchmark option" {:options options})
  (let [options (merge defaults options)]
    (doseq [[key lo hi] [[:nodes 1024 20000] [:forks 1 100]
                        [:updates-per-fork 1 10] [:batch-updates 1 1000]
                        [:lookup-count 1 200000] [:rounds 1 5]]]
      (check! (and (integer? (get options key)) (<= lo (get options key) hi))
              "Benchmark option outside bounded profile" {:option key :min lo :max hi}))
    options))

(defn- digest [text]
  (.formatHex (HexFormat/of)
              (.digest (MessageDigest/getInstance "SHA-256")
                       (.getBytes ^String text StandardCharsets/UTF_8))))

(defn- fixture [n]
  (let [entries (mapv (fn [id]
                        [id (if (= id 1)
                              {:id id :kind :synthetic-argument :operands []}
                              {:id id :kind :synthetic-add :operands [(dec id) 1]})])
                      (range 1 (inc n)))
        nodes (into {} entries)
        uses (reduce-kv
               (fn [index user row]
                 (reduce-kv (fn [index slot target]
                              (update index target (fnil conj #{}) [user slot]))
                            index (:operands row))) {} nodes)
        ;; Explicit ordered values, never map/set print order, define this digest.
        text (pr-str (mapv (fn [[id row]] [id (:kind row) (:operands row)]) entries))]
    {:entries entries :use-entries (vec (sort-by key uses))
     :baseline {:nodes nodes :uses uses} :sha256 (digest text)}))

(defn- timed [f]
  (let [start (System/nanoTime) result (f)]
    {:value result :ms (/ (double (- (System/nanoTime) start)) 1000000.0)}))

(defn- retarget [snapshot user]
  (let [old (get-in snapshot [:nodes user :operands 0]) site [user 0]
        remaining (disj (get (:uses snapshot) old #{}) site)]
    (check! (not= 1 old) "Benchmark update unexpectedly became a no-op" {:user user})
    (-> snapshot
        (assoc-in [:nodes user :operands 0] 1)
        (update :uses #(if (seq remaining) (assoc % old remaining) (dissoc % old)))
        (update-in [:uses 1] (fnil conj #{}) site))))

(defn- users [n start count]
  (mapv #(+ 3 (mod (+ start %) (- n 2))) (range count)))

(defn- iteration-checksum [snapshot]
  [(reduce-kv (fn [total id row]
                (+ total id (reduce + 0 (:operands row)))) 0 (:nodes snapshot))
   (reduce-kv (fn [total target sites]
                (+ total target (count sites))) 0 (:uses snapshot))])

(defn- lookup-checksum [snapshot n lookup-count]
  (loop [i 0 checksum 0]
    (if (= i lookup-count) checksum
      (let [id (inc (mod (* i 8191) n)) row (get (:nodes snapshot) id)]
        (recur (inc i) (+ checksum (:id row) (count (:operands row))))))))

(defn- sample [label builder fixture options]
  (let [{:keys [nodes forks updates-per-fork batch-updates lookup-count]} options
        built (timed #(hash-map :nodes (builder (:entries fixture))
                                :uses (builder (:use-entries fixture))))
        base (:value built)
        plans (mapv #(users nodes (* % updates-per-fork) updates-per-fork) (range forks))
        retained (timed #(mapv (fn [plan] (reduce retarget base plan)) plans))
        batch-plan (users nodes 0 batch-updates)
        batch (timed #(reduce retarget base batch-plan))
        looked-up (timed #(lookup-checksum (:value batch) nodes lookup-count))
        iterated (timed #(iteration-checksum (:value batch)))
        expected-base (:baseline fixture)
        expected-batch (reduce retarget expected-base batch-plan)]
    ;; All retained roots remain live through every timed operation and validation.
    ;; Build reference results OUTSIDE timings. They test the wrapper, not LLVM.
    (check! (= expected-base base) "An earlier root changed" {:backend label})
    (check! (= expected-batch (:value batch)) "Batch result differs" {:backend label})
    (doseq [[index plan branch] (map vector (range) plans (:value retained))]
      (check! (= (reduce retarget expected-base plan) branch)
              "Retained fork changed or differs" {:backend label :fork index})
      (check! (= #{[2 0] [2 1]}
                  (set (filter #(= 2 (first %)) (get-in branch [:uses 1]))))
              "Repeated operand positions collapsed" {:backend label :fork index})
      (check! (identical? (get-in base [:nodes 1]) (get-in branch [:nodes 1]))
              "Untouched node payload was copied" {:backend label :fork index}))
    (check! (= (lookup-checksum expected-batch nodes lookup-count) (:value looked-up))
            "Lookup checksum differs" {:backend label})
    (check! (= (iteration-checksum expected-batch) (:value iterated))
            "Iteration checksum differs" {:backend label})
    {:backend label :milliseconds {:build (:ms built) :retained-forks (:ms retained)
                                   :batch (:ms batch) :lookup (:ms looked-up)
                                   :reduce-kv (:ms iterated)}
     :validated true :retained-roots (inc forks)
     :lookup-checksum (:value looked-up) :iteration-checksum (:value iterated)}))

(defn run
  "Run a bounded synthetic table workload. Every backend retains all fork roots
   until validation. No forced GC/RSS sampling; report each round without claiming
   statistical significance. First round includes cold/JIT effects. OPTIONS may
   reduce the default work; limits cap it at20k nodes/100 forks/5 rounds."
  ([] (run {}))
  ([options]
   (let [options (options! options) fixture (fixture (:nodes options))
         backends [[:clojure-persistent-map #(into {} %)]
                   [:bifurcan-intmap-clojure-proxy bifurcan/node-map]]
         rounds (mapv (fn [round]
                        ;; Alternate order to expose, not eliminate, JVM/GC bias.
                        {:round round
                         :samples (mapv (fn [[label builder]] (sample label builder fixture options))
                                        (if (even? round) backends (vec (reverse backends))))})
                      (range (:rounds options)))]
     {:scope :synthetic-persistent-table-comparison :llvm-input false
      :fixture {:profile :chain-with-repeated-operands-v1 :sha256 (:sha256 fixture)
                :nodes (:nodes options) :operand-sites (* 2 (dec (:nodes options)))}
      :configuration options :bifurcan-version "0.2.0-rc1"
      :jvm {:java-version (System/getProperty "java.version")
            :vm-name (System/getProperty "java.vm.name")}
      :includes [:wrapper-dispatch :persistent-update :reverse-use-maintenance]
      :excludes [:native-parse :native-verification :graph-digest :patch-replay :disk-io
                 :fixture-generation :result-validation :rss-measurement]
      :caveats ["Wall times only; not a microbenchmark or statistical comparison."
                "No warmup phase or forced GC; first round and execution order can matter."
                "Bifurcan construction uses its private linear builder; updates use forked tables."
                "Lookup and reduce-kv use the final batch result; all fork roots remain retained."
                "No mandatory backend migration or blanket performance winner is inferred."]
      :rounds rounds})))

(defn -main [& args]
  (check! (<= (count args) 1) "Pass at most one EDN options map" {})
  (pprint/pprint (run (if-let [text (first args)] (edn/read-string text) {}))))
