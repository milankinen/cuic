(ns cuic.impl.exception
  (:import (cuic ExecutionException WaitTimeoutException)
           (com.github.kklisura.cdt.services.exceptions ChromeDevToolsInvocationException)))

(defn- ex-type [ex]
  (if (instance? ExecutionException ex)
    (.getType ex)))

(defn retryable [^String msg & [data]]
  (ExecutionException. msg true nil (or data {}) nil))

(defn timeout [expr reason]
  (WaitTimeoutException. "Wait timeout exceeded" {:expression expr :reason reason} nil))

(defn js-error [description]
  (ExecutionException. "JavaScript error" false :js-error {:description description} nil))

(defn stale-node [cause]
  (ExecutionException. "Node not found from DOM" true :stale {} cause))

(defn devtools-error [cause]
  (ExecutionException. "Protocol exception occurred" false :protocol {} cause))

(defn retryable? [ex]
  (and (instance? ExecutionException ex)
       (true? (.isRetryable ex))))

(defn stale-node? [ex]
  (= :stale (ex-type ex)))

(defmacro call-node [call-expr]
  `(try
     ~call-expr
     (catch ChromeDevToolsInvocationException e#
       (if (= -32000 (.getCode e#))
         (throw (stale-node e#))
         (throw (devtools-error e#))))))

(defmacro call [call-expr]
  `(try
     ~call-expr
     (catch ChromeDevToolsInvocationException e#
       (throw (devtools-error e#)))))

(defmacro with-stale-ignored [expr]
  `(try
     ~expr
     (catch ExecutionException e#
       (if-not (stale-node? e#) (throw e#))
       nil)))
