(ns cuic.impl.retry
  (:require [cuic.impl.exception :as ex]))

(defn try-run [f]
  (try
    {:value (f)}
    (catch Exception e
      (if-not (ex/retryable? e)
        (throw e))
      {:error e})))

(defn loop* [f timeout expr-form]
  (loop [start (System/currentTimeMillis)]
    (let [result (try-run f)]
      (if (:value result)
        (:value result)
        (do (when (> (- (System/currentTimeMillis) start) timeout)
              (let [reason (or (:error result)
                               (str "Latest value was " (pr-str (:value result))))]
                (throw (ex/timeout expr-form reason))))
            (Thread/sleep 50)
            (recur start))))))

