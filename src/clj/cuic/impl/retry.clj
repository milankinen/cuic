(ns cuic.impl.retry
  (:require [cuic.impl.exception :as ex]))

(defn- t []
  (System/currentTimeMillis))

(defn- try-run [f]
  (try
    {:value (f)}
    (catch Throwable e
      (if (ex/retryable? e)
        {:error e}
        (throw e)))))

(defn loop* [f expr-form timeout-ms]
  {:pre [(fn? f)
         (integer? timeout-ms)]}
  (loop [start (t)]
    (let [{:keys [value error]} (try-run f)]
      (if-not value
        (do (when (> (- (t) start) timeout-ms)
              (throw (ex/timeout-ex expr-form error)))
            (Thread/sleep 50)
            (recur start))
        value))))
