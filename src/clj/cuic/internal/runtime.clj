(ns ^:no-doc cuic.internal.runtime
  (:require [clojure.string :as string]
            [clojure.java.io :as io]
            [cuic.internal.node :refer [node? get-object-id get-node-cdt]]
            [cuic.internal.util :refer [cuic-ex safe-str]]
            [cuic.internal.cdt :refer [invoke]])
  (:import (cuic DevtoolsProtocolException)
           (java.io Writer)))

(set! *warn-on-reflection* true)

(def ^:private js-fn-template
  "async function(%s) {
     return await (async function() { %s }).call(this)
   }")

(defn- get-window-object-id [{:keys [cdt]}]
  (-> (invoke {:cdt  cdt
               :cmd  "Runtime.evaluate"
               :args {:expression "window"}})
      (get-in [:result :objectId])))

(defn- validate-arg-value [val]
  (cond
    (or (string? val)
        (number? val)
        (boolean? val)
        (nil? val)) :ok
    (map? val)
    (doseq [[k v] val]
      (when-not (or (keyword? k)
                    (string? k)
                    (symbol? k))
        (throw (cuic-ex "Object keys must be either keywords, strings or symbols"
                        "but got" (safe-str k))))
      (validate-arg-value v))
    (coll? val)
    (doseq [v val]
      (validate-arg-value v))
    :else
    (throw (cuic-ex "Only JSON primitive values, maps and collections accepted"
                    "as call argument values but got" (safe-str val)))))

(defn- validate-arg-key [key]
  (when-not (simple-keyword? key)
    (throw (cuic-ex "Call arguments keys must be simple keywords "
                    "but got" (safe-str key)))))

(defn- validate-args [args]
  (doseq [[k v] args]
    (validate-arg-key k)
    (validate-arg-value v)))

(def ^:private runtime-js-src
  (delay (slurp (io/resource "cuic_runtime.js"))))


;;;

(defrecord Window [cdt])

(defmethod print-method Window [_ writer]
  (.write ^Writer writer "#window {}"))

(defn initialize [cdt]
  (invoke {:cdt  cdt
           :cmd  "Page.addScriptToEvaluateOnNewDocument"
           :args {:source @runtime-js-src}}))

(defn get-window [cdt]
  (->Window cdt))

(defn window? [val]
  (instance? Window val))

(defn exec-js-code
  [{:keys [code
           this
           args
           return-by-value]
    :or   {args            {}
           return-by-value true}}]
  {:pre [(string? code)
         (or (window? this)
             (node? this))]}
  (try
    (validate-args args)
    (let [sorted-args (sort-by first args)
          args-s (string/join ", " (map (comp name first) sorted-args))
          cdt (if (node? this)
                (get-node-cdt this)
                (:cdt this))
          object-id (if (node? this)
                      (get-object-id this)
                      (get-window-object-id this))
          result (invoke {:cdt  cdt
                          :cmd  "Runtime.callFunctionOn"
                          :args {:functionDeclaration (format js-fn-template args-s code)
                                 :awaitPromise        true
                                 :arguments           (mapv #(do {:value (second %)}) sorted-args)
                                 :returnByValue       return-by-value
                                 :objectId            object-id}})]
      (if-let [ex-details (:exceptionDetails result)]
        {:error (get-in ex-details [:exception :description])}
        {:return (get-in result [:result :value])}))
    (catch DevtoolsProtocolException ex
      (if (re-find #"Object couldn't be returned by value" (ex-message ex))
        (throw (cuic-ex "Only primitives, objects and arrays accepted"
                        "as return value"))
        (throw ex)))))

(defn scroll-into-view-if-needed [node]
  {:pre [(node? node)]}
  (exec-js-code {:code "return __CUIC__.scrollIntoView(this)"
                 :this node}))
