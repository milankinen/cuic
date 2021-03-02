(ns ^:no-doc cuic.internal.runtime
  (:require [clojure.string :as string]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [cuic.internal.util :refer [cuic-ex safe-str]]
            [cuic.internal.page :refer [page? get-page-loader-id get-page-cdt]]
            [cuic.internal.cdt :refer [invoke ex-code]])
  (:import (java.io Writer)
           (cuic DevtoolsProtocolException)
           (cuic.internal StaleObjectException)))

(set! *warn-on-reflection* true)

(def ^:private runtime-js-src
  (delay (slurp (io/resource "cuic_runtime.js"))))

(defn- dispose [page {:keys [objectId]}]
  (try
    ;; Try to release the object ignoring errors; failure in
    ;; this command execution does not affect the tests being run,
    ;; it just prevents browser GC to remove referenced js objects
    ;; before page gets reloaded
    (invoke {:cdt    (get-page-cdt page)
             :cmd    "Runtime.releaseObject"
             :args   {:objectId objectId}
             :block? false})
    (catch Exception _)))

(deftype RemoteObjectRef [page loader-id remote-object]
  Object
  (finalize [_]
    (when (= (get-page-loader-id page) loader-id)
      (dispose page remote-object))))

(defrecord JSObject [page remote-object __ref__])

(defmulti js-object->tagged-literal (fn [js-obj] (get-in js-obj [:remote-object :subtype])))

(defmethod js-object->tagged-literal :default [js-obj]
  (if (= "Window" (get-in js-obj [:remote-object :className]))
    (str "#window {}")
    (str "#js-object " (pr-str (select-keys (:remote-object js-obj) [:description])))))

(defn- create-handle [page remote-object]
  {:pre [(page? page)
         (map? remote-object)
         (:objectId remote-object)]}
  (let [loader-id (get-page-loader-id page)
        ref (RemoteObjectRef. page loader-id remote-object)]
    (->JSObject page remote-object ref)))

(defmethod print-method JSObject [handle writer]
  (.write ^Writer writer ^String (js-object->tagged-literal handle)))

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

(defn- unwrap [page {:keys [result exceptionDetails]}]
  (if exceptionDetails
    (do (doseq [remote-obj [result (:exception exceptionDetails)]
                :when (:objectId remote-obj)]
          (dispose page remote-obj))
        (let [msg (or (->> (get-in exceptionDetails [:exception :preview :properties])
                           (filter (comp #{"message"} :name))
                           (map :value)
                           (first))
                      (get-in exceptionDetails [:exception :description])
                      (get result :description))]
          (throw (cuic-ex "JavaScript error occurred:" msg))))
    (try
      (let [props (->> (invoke {:cdt  (get-page-cdt page)
                                :cmd  "Runtime.getProperties"
                                :args {:objectId      (:objectId result)
                                       :ownProperties true}})
                       (:result)
                       (map (juxt :name :value))
                       (into {}))]
        (->> (if-let [refs (get-in props ["refs" :value])]
               (json/read-str refs :key-fn keyword)
               [])
             (map-indexed vector)
             (reduce (fn [r [i p]]
                       (let [o (create-handle page (get props (str "ref_" i)))
                             p' (mapv #(if (string? %) (keyword %) %) p)]
                         (if (seq p')
                           (assoc-in r p' o)
                           o)))
                     (json/read-str (get-in props ["str" :value]) :key-fn keyword))))
      (finally
        (dispose page result)))))


;;;

(defn initialize [cdt]
  (invoke {:cdt  cdt
           :cmd  "Page.addScriptToEvaluateOnNewDocument"
           :args {:source @runtime-js-src}}))

(defn js-object? [x]
  (instance? JSObject x))

(defn get-object-id [obj]
  (get-in obj [:remote-object :objectId]))

(defn get-object-page [obj]
  {:pre [(js-object? obj)]}
  (:page obj))

(defn get-object-cdt [obj]
  (-> (get-object-page obj)
      (get-page-cdt)))

(defn get-remote-object [obj]
  {:pre [(js-object? obj)]}
  (:remote-object obj))

(defn eval-sync [{:keys [page code]}]
  {:pre [(page? page)
         (string? code)]}
  (->> (invoke {:cdt  (get-page-cdt page)
                :cmd  "Runtime.evaluate"
                :args {:expression    (format "__CUIC__.wrapResult((function() { %s }).call(this))" code)
                       :returnByValue false}})
       (unwrap page)))

(defn call-sync
  [{:keys [code
           this
           args]
    :or   {args {}}}]
  {:pre [(string? code)
         (js-object? this)
         (map? args)]}
  (try
    (validate-args args)
    (let [sorted-args (sort-by first args)
          args-s (string/join ", " (map (comp name first) sorted-args))
          page (:page this)]
      (->> (invoke {:cdt  (get-page-cdt page)
                    :cmd  "Runtime.callFunctionOn"
                    :args {:functionDeclaration (format "function(%s) { return __CUIC__.wrapResult((function() { %s }).call(this)) }" args-s code)
                           :awaitPromise        false
                           :arguments           (mapv #(do {:value (second %)}) sorted-args)
                           :returnByValue       false
                           :objectId            (get-object-id this)}})
           (unwrap page)))
    (catch DevtoolsProtocolException ex
      (when (and (= -32000 (ex-code ex))
                 (= "Cannot find context with specified id" (ex-message ex)))
        (throw (StaleObjectException.)))
      (throw ex))))

(defn call-async
  [{:keys [code
           this
           args]
    :or   {args {}}}]
  {:pre [(string? code)
         (js-object? this)
         (map? args)]}
  (try
    (validate-args args)
    (let [sorted-args (sort-by first args)
          args-s (string/join ", " (map (comp name first) sorted-args))
          page (:page this)]
      (->> (invoke {:cdt  (get-page-cdt page)
                    :cmd  "Runtime.callFunctionOn"
                    :args {:functionDeclaration (format "async function(%s) { return __CUIC__.wrapResult(await (async function() { %s }).call(this)) }" args-s code)
                           :awaitPromise        true
                           :arguments           (mapv #(do {:value (second %)}) sorted-args)
                           :returnByValue       false
                           :objectId            (get-object-id this)}})
           (unwrap page)))
    (catch DevtoolsProtocolException ex
      (when (and (= -32000 (ex-code ex))
                 (= "Cannot find context with specified id" (ex-message ex)))
        (throw (StaleObjectException.)))
      (throw ex))))

(defn get-window [page]
  (eval-sync {:page page :code "return window"}))

(defn scroll-into-view-if-needed [element]
  {:pre [(js-object? element)]}
  (call-async {:code "return __CUIC__.scrollIntoView(this)"
               :this element}))
