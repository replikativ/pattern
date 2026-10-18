(ns pattern.llvm.test-runner
  (:require [clojure.test :as test]
            [pattern.llvm.graph-test]
            [pattern.llvm.integration-test]))

(defn -main [& arguments]
  (let [optional? (= ["bifurcan"] (vec arguments))
        _ (when (and (seq arguments) (not optional?))
            (throw (ex-info "Only optional argument: bifurcan" {})))
        _ (when optional? (require 'pattern.llvm.bifurcan-test))
        namespaces (cond-> ['pattern.llvm.graph-test 'pattern.llvm.integration-test]
                     optional? (conj 'pattern.llvm.bifurcan-test))
        result (apply test/run-tests namespaces)]
    (shutdown-agents)
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
