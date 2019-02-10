(ns cuic.impl.exception
  (:import (cuic ExecutionException WaitTimeoutException)
           (com.github.kklisura.cdt.services.exceptions ChromeDevToolsInvocationException)))

(defn- ex-type [ex]
  (if (instance? ExecutionException ex)
    (.getType ex)))

(defn retryable [^String msg]
  (ExecutionException. msg true nil nil))

(defn timeout [actual cause]
  (WaitTimeoutException. actual cause))

(defn js-error [description]
  (ExecutionException. (str "JavaScript evaluation error: " description) false :js-error nil))

(defn stale-node [cause]
  (ExecutionException. "Node not found from DOM" true :stale cause))

(defn devtools-error [cause]
  (ExecutionException. "Protocol exception occurred" false :protocol cause))

(defn mutation-failure [cause mutation-description]
  (let [cause (loop [c cause]
                (if (or (= :mutation (ex-type c))
                        (instance? WaitTimeoutException c))
                  (recur (.getCause c))
                  c))]
    (ExecutionException. (str "Could not execute mutation " mutation-description
                              (if cause (str ", cause: " cause)))
                         false :mutation-failure cause)))

(defn retryable? [ex]
  (and (instance? ExecutionException ex)
       (true? (.isRetryable ex))))

(defn stale-node? [ex]
  (= :stale (ex-type ex)))

(defn call-node [op]
  (try
    (op)
    (catch ChromeDevToolsInvocationException e
      (if (= -32000 (.getCode e))
        (throw (stale-node e))
        (throw (devtools-error e))))))

(defn call [op]
  (try
    (op)
    (catch ChromeDevToolsInvocationException e
      (throw (devtools-error e)))))
