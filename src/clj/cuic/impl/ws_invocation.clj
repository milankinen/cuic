(ns cuic.impl.ws-invocation
  (:require [cuic.impl.exception :as ex]))

(defn call [f & args]
  (try
    (apply f args)
    (catch Exception e
      (case (:code (:error (ex-data e)))
        -32000 (throw (ex/stale-node))
        (throw e)))))
