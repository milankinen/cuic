(ns cuic.impl.exception
  (:import (cuic ExecutionException WaitTimeoutException)
           (com.github.kklisura.cdt.services.exceptions ChromeDevToolsInvocationException)))

(defn- eex-data [ex]
  (if (instance? ExecutionException ex)
    (.-data ex)))

(defn retryable [^String msg & [data]]
  (ExecutionException. msg true data))

(defn timeout [expr reason]
  (-> (str "Timeout waiting for expression: " expr
           "\nReason: " reason)
      (WaitTimeoutException.)))

(defn js-error [description]
  (ExecutionException. (str "JavaScript error:\n" description) false {:description description}))

(defn stale-node []
  (retryable "Can't use node because it's already removed from the DOM" {::type :stale}))

(defn node-not-visible []
  (retryable "DOM node is not visible" {::type :not-visible}))

(defn retryable? [ex]
  (and (instance? ExecutionException ex)
       (true? (.-retryable ex))))

(defn stale-node? [ex]
  (= :stale (::type (eex-data ex))))

(defmacro call [call-expr]
  `(try
     ~call-expr
     (catch ChromeDevToolsInvocationException e#
       (if (and (= -32000 (.getCode e#))
                (= "Cannot find context with specified id" (.getMessage e#)))
         (throw (stale-node))
         (throw e#)))))

(defmacro with-stale-ignored [expr]
  `(try
     ~expr
     (catch ExecutionException e#
       (if-not (stale-node? e#) (throw e#))
       nil)))
