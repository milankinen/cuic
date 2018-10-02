(ns cuic.impl.retry
  (:require [cuic.impl.exception :as ex]))

(defn try-run [f]
  (try
    {:value (f)}
    (catch Throwable e
      (if (ex/retryable? e)
        {:error e}
        (throw e)))))

(defn loop* [f timeout expr-form]
  (loop [start (System/currentTimeMillis)]
    (let [{:keys [value error] :as res} (try-run f)]
      (if value
        value
        (do (when (> (- (System/currentTimeMillis) start) timeout)
              (let [latest (if (contains? res :value)
                             (str ", latest value was: " (pr-str value))
                             (str ", could not get the value to due exception: " (.getMessage error)))]
                (throw (ex/timeout expr-form latest error))))
            (Thread/sleep 50)
            (recur start))))))

