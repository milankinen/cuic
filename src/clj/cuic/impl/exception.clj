(ns cuic.impl.exception
  (:import (cuic ExecutionException WaitTimeoutException AbortTestError)))

(defn- strip-stacktrace [ex]
  (let [st       (.getStackTrace ex)
        stripped (drop-while #(re-find #"^cuic\." (.toString %)) (seq st))]
    (.setStackTrace ex (into-array StackTraceElement stripped))
    ex))

(defn- ex-type [ex]
  (if (instance? ExecutionException ex)
    (.getType ex)))

(defn retryable-ex [& msgs]
  (-> (ExecutionException. (apply str msgs) true nil nil)
      (strip-stacktrace)))

(defn timeout-ex [form cause]
  (-> (WaitTimeoutException. (str form) cause)
      (strip-stacktrace)))

(defn js-execution-ex [description]
  (-> (ExecutionException. (str "JavaScript evaluation error: " description) false :js-error nil)
      (strip-stacktrace)))

(defn protocol-ex [cause]
  (-> (ExecutionException. "Protocol exception occurred" false :protocol cause)
      (strip-stacktrace)))

(defn abort-test-ex []
  (AbortTestError.))

(defn retryable? [ex]
  (and (instance? ExecutionException ex)
       (true? (.isRetryable ex))))
