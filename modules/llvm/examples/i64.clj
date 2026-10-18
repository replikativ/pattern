(ns pattern.llvm.examples.i64
  "Small experimental Clojure-form frontend, not a general Clojure compiler.
   All arguments/results are signed i64; conditions are i1 comparisons."
  (:require [clojure.string :as str]))

(defn- require! [condition message data]
  (when-not condition (throw (ex-info message data))))
(defn- fresh [state prefix]
  (str prefix (swap! (:serial state) inc)))
(defn- block! [state label]
  (swap! (:blocks state) conj [label (atom [])])
  (reset! (:current state) label))
(defn- emit! [state line]
  (swap! (second (peek @(:blocks state))) conj line))
(defn- value! [state type instruction]
  (let [value (str "%" (fresh state "v"))]
    (emit! state (str value " = " instruction))
    {:type type :value value}))
(defn- typed! [value type]
  (require! (= type (:type value)) "Expression type outside the supported subset"
            {:expected type :actual (:type value)})
  (:value value))
(defn- bindings! [bindings]
  (require! (and (vector? bindings) (even? (count bindings))
                (every? #(and (symbol? %) (not= '& %) (nil? (namespace %))) (take-nth 2 bindings))
                (or (empty? bindings) (apply distinct? (take-nth 2 bindings))))
            "Expected distinct simple binding names and initializer expressions" {:bindings bindings}))

(declare expression!)
(defn- arguments! [state env forms signatures loop-context]
  (mapv #(expression! state env % signatures loop-context false) forms))

(defn- branch! [state env [condition yes no] signatures loop-context tail?]
  (let [test (expression! state env condition signatures loop-context false)
        yes-label (fresh state "yes") no-label (fresh state "no")
        join-label (fresh state "join")]
    (emit! state (str "br i1 " (typed! test "i1") ", label %" yes-label ", label %" no-label))
    (let [incoming
          (reduce (fn [incoming [label form]]
                    (block! state label)
                    (if-let [value (expression! state env form signatures loop-context tail?)]
                      (let [end @(:current state)]
                        (emit! state (str "br label %" join-label))
                        (conj incoming [value end]))
                      incoming)) [] [[yes-label yes] [no-label no]])]
      (when (seq incoming)
        (block! state join-label)
        (let [type (:type (ffirst incoming))]
          (require! (every? #(= type (:type (first %))) incoming) "if branch types differ" {})
          (value! state type
                  (str "phi " type " "
                       (str/join ", " (map (fn [[value label]]
                                             (str "[ " (:value value) ", %" label " ]")) incoming)))))))))

(defn- division! [state opcode a b]
  ;; Avoid LLVM undefined behavior. Native failure traps rather than providing
  ;; Clojure exception objects; this boundary is part of the example contract.
  (let [a (typed! a "i64") b (typed! b "i64")
        zero (value! state "i1" (str "icmp eq i64 " b ", 0"))
        minimum (value! state "i1" (str "icmp eq i64 " a ", -9223372036854775808"))
        negative-one (value! state "i1" (str "icmp eq i64 " b ", -1"))
        overflow (value! state "i1" (str "and i1 " (:value minimum) ", " (:value negative-one)))
        invalid (value! state "i1" (str "or i1 " (:value zero) ", " (:value overflow)))
        trap-label (fresh state "trap") valid-label (fresh state "divide")]
    (emit! state (str "br i1 " (:value invalid) ", label %" trap-label ", label %" valid-label))
    (block! state trap-label)
    (emit! state "call void @llvm.trap()")
    (emit! state "unreachable")
    (block! state valid-label)
    (value! state "i64" (str opcode " i64 " a ", " b))))

(defn- loop! [state env [bindings body] signatures]
  (bindings! bindings)
  (require! (seq bindings) "Empty loops are outside this example subset" {})
  (let [pairs (partition 2 bindings)
        [env initials] (reduce (fn [[env values] [name form]]
                                 (let [value (expression! state env form signatures nil false)]
                                   (typed! value "i64")
                                   [(assoc env name value) (conj values value)])) [env []] pairs)
        predecessor @(:current state)
        header (fresh state "loop")
        incoming (atom [[initials predecessor]])
        registers (mapv (fn [_] {:type "i64" :value (str "%" (fresh state "loopvar"))}) pairs)
        context {:header header :incoming incoming :arity (count pairs)}]
    (emit! state (str "br label %" header))
    (block! state header)
    (doseq [[slot register] (map-indexed vector registers)]
      ;; Backedges are discovered while compiling the loop body, then rendered.
      (emit! state (fn [] (str (:value register) " = phi i64 "
                               (str/join ", " (map (fn [[values label]]
                                                     (str "[ " (:value (nth values slot)) ", %" label " ]"))
                                                   @incoming))))))
    (expression! state (into env (map vector (map first pairs) registers)) body signatures context true)))

(def arithmetic {'unchecked-add "add" 'unchecked-subtract "sub" 'unchecked-multiply "mul"
                 'bit-and "and" 'bit-or "or" 'bit-xor "xor"})
(def comparisons {'< "slt" '<= "sle" '> "sgt" '>= "sge" '= "eq" 'not= "ne"})

(defn- expression! [state env form signatures loop-context tail?]
  (cond
    (integer? form)
    (do (require! (and (instance? Long form) (<= Long/MIN_VALUE form Long/MAX_VALUE))
                  "Only signed64 Long literals are supported; promotion is outside this subset" {:form form})
        {:type "i64" :value (str form)})

    (symbol? form)
    (or (get env form) (throw (ex-info "Unbound local" {:form form})))

    (seq? form)
    (let [[operation & args] form]
      (require! (not (contains? env operation)) "Local call targets are outside this subset"
                {:operation operation})
      (case operation
        if (do (require! (= 3 (count args)) "if requires all three expressions" {:form form})
               (branch! state env args signatures loop-context tail?))
        let (let [[bindings body] args]
              (require! (= 2 (count args)) "let requires one body expression" {:form form})
              (bindings! bindings)
              (expression! state
                (reduce (fn [env [name value]]
                          (assoc env name (expression! state env value signatures loop-context false)))
                        env (partition 2 bindings)) body signatures loop-context tail?))
        loop (do (require! (= 2 (count args)) "loop requires one body expression" {:form form})
                 (loop! state env args signatures))
        recur (do (require! (and tail? loop-context (= (:arity loop-context) (count args)))
                            "recur must be in loop tail position with matching arity" {:form form})
                  (let [values (arguments! state env args signatures loop-context)]
                    (doseq [value values] (typed! value "i64"))
                    (swap! (:incoming loop-context) conj [values @(:current state)])
                    (emit! state (str "br label %" (:header loop-context)))
                    nil))
        (let [values (arguments! state env args signatures loop-context)]
          (cond
            (contains? signatures operation)
            (let [{:keys [native arity]} (get signatures operation)]
              (require! (= arity (count values)) "Wrong function arity" {:operation operation})
              (value! state "i64" (str "call i64 @" native "("
                                       (str/join ", " (map #(str "i64 " (typed! % "i64")) values)) ")")))
            (or (contains? arithmetic operation) (contains? comparisons operation) (#{'quot 'rem} operation))
            (do (require! (= 2 (count values)) "Primitive requires exactly two arguments" {:operation operation})
                (let [[a b] values]
                  (if (#{'quot 'rem} operation)
                    (division! state (if (= 'quot operation) "sdiv" "srem") a b)
                    (value! state (if (contains? comparisons operation) "i1" "i64")
                            (str (or (get arithmetic operation)
                                     (str "icmp " (get comparisons operation)))
                                 " i64 " (typed! a "i64") ", " (typed! b "i64"))))))
            (= 'unchecked-inc operation)
            (do (require! (= 1 (count values)) "unchecked-inc requires one argument" {})
                (value! state "i64" (str "add i64 " (typed! (first values) "i64") ", 1")))
            :else (throw (ex-info "Unsupported operation; no implicit host fallback" {:operation operation}))))))

    :else (throw (ex-info "Unsupported source form" {:form form}))))

(defn compile-module
  "Compile a vector of single-body, single-arity defn forms to LLVM assembly.
   No source forms are evaluated. See the example README for semantic boundaries."
  [definitions]
  (require! (and (vector? definitions) (seq definitions)) "Expected nonempty definition vector" {})
  (doseq [[head name params :as definition] definitions]
    (require! (and (= 'defn head) (= 4 (count definition)) (symbol? name)
                  (re-matches #"[A-Za-z][A-Za-z0-9_?!-]*" (str name))
                  (vector? params) (every? #(and (symbol? %) (not= '& %) (nil? (namespace %))) params)
                  (or (empty? params) (apply distinct? params)))
              "Expected simple single-body defn" {:definition definition}))
  (let [signatures (into {} (map (fn [[_ name params]]
                                  [name {:native (str "clj_" (str/replace (str name) #"[^A-Za-z0-9_]" "_"))
                                         :arity (count params)}]) definitions))]
    (require! (= (count definitions) (count signatures)
                 (count (set (map :native (vals signatures))))) "Duplicate source/native function name" {})
    (str "source_filename = \"clojure-i64-example\"\n\ndeclare void @llvm.trap()\n\n"
         (str/join "\n\n"
           (for [[_ name params body] definitions]
             (let [state {:serial (atom 0) :blocks (atom []) :current (atom nil)}
                   env (into {} (map-indexed (fn [i name] [name {:type "i64" :value (str "%arg" i)}]) params))]
               (block! state "entry")
               (when-let [result (expression! state env body signatures nil true)]
                 (emit! state (str "ret i64 " (typed! result "i64"))))
               (str "define i64 @" (get-in signatures [name :native]) "("
                    (str/join ", " (map #(str "i64 " (:value (get env %))) params)) ") {\n"
                    (str/join "\n" (for [[label lines] @(:blocks state)]
                                     (str label ":\n" (str/join "\n" (map #(str "  " (if (fn? %) (%) %)) @lines)))))
                    "\n}")))) "\n")))
