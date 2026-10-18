(ns pattern.llvm.examples.primes)

;; Ordinary executable Clojure. The experimental frontend compiles these same
;; defn forms, not a second hand-written implementation of the kernel.
(defn prime-flag [n]
  (if (< n 2)
    0
    (loop [divisor 2]
      (if (> divisor (quot n divisor))
        1
        (if (= (rem n divisor) 0)
          0
          (recur (unchecked-inc divisor)))))))

(defn count-primes [limit]
  (loop [n 2 total 0]
    (if (> n limit)
      total
      (recur (unchecked-inc n) (unchecked-add total (prime-flag n))))))

(defn sum-primes [limit]
  (loop [n 2 total 0]
    (if (> n limit)
      total
      (let [contribution (if (= (prime-flag n) 1) n 0)]
        (recur (unchecked-inc n) (unchecked-add total contribution))))))
