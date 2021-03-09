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

(declare js-object?)

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

(defn- coerce-arg-value! [obj-refs path val]
  (cond
    (or (string? val)
        (number? val)
        (boolean? val)
        (nil? val)) val
    (js-object? val)
    (do (vswap! obj-refs conj [path val])
        nil)
    (map? val)
    (->> (for [[k v] val]
           (if-not (or (keyword? k)
                       (string? k)
                       (symbol? k))
             (throw (cuic-ex "Object keys must be either keywords, strings or symbols"
                             "but got" (safe-str k)))
             [k (coerce-arg-value! obj-refs (conj path k) v)]))
         (into {}))
    (coll? val)
    (->> (map-indexed vector val)
         (mapv (fn [[i v]] (coerce-arg-value! obj-refs (conj path i) v))))
    :else
    (throw (cuic-ex "Only JSON primitive values, JS object references, maps and collections"
                    "are accepted as call argument values but got" (safe-str val)))))

(defn- validate-arg-key [key]
  (when-not (simple-keyword? key)
    (throw (cuic-ex "Call arguments keys must be simple keywords "
                    "but got" (safe-str key)))))

(defn- coerce-args [args]
  (doseq [k (keys args)]
    (validate-arg-key k))
  (let [obj-refs (volatile! [])
        coerced (coerce-arg-value! obj-refs [] args)
        paths (mapv first @obj-refs)
        handles (mapv second @obj-refs)]
    [coerced paths handles]))

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
           :args {:source @runtime-js-src}})
  (invoke {:cdt  cdt
           :cmd  "Runtime.evaluate"
           :args {:expression    @runtime-js-src
                  :returnByValue true}}))

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

(defn- call* [{:keys [code
                      this
                      args]
               :or   {args {}}}
              {:keys [async?]}]
  {:pre [(string? code)
         (js-object? this)
         (map? args)
         (boolean? async?)]}
  (try
    (let [[coerced-args obj-paths obj-handles] (coerce-args args)
          page (:page this)
          tmpl (if (seq obj-paths)
                 (if async?
                   "async function() { let %s = __CUIC__.wrapArgs([...arguments]); return __CUIC__.wrapResult(await (async function() { %s }).call(this)) }"
                   "function(%s) { let %s = __CUIC__.wrapArgs([...arguments]); return __CUIC__.wrapResult((function() { %s }).call(this)) }")
                 ;; optimize primitive-only path
                 (if async?
                   "async function(%s) { return __CUIC__.wrapResult(await (async function() { %s }).call(this)) }"
                   "function(%s) { return __CUIC__.wrapResult((function() { %s }).call(this)) }"))
          arg-decomp (str "{" (string/join "," (map (comp name first) args)) "}")
          js-args (if (seq obj-paths)
                    (->> (map #(do {:objectId (get-object-id %)}) obj-handles)
                         (cons {:value obj-paths})
                         (cons {:value coerced-args}))
                    [{:value coerced-args}])]
      (->> (invoke {:cdt  (get-page-cdt page)
                    :cmd  "Runtime.callFunctionOn"
                    :args {:functionDeclaration (format tmpl arg-decomp code)
                           :awaitPromise        async?
                           :arguments           js-args
                           :returnByValue       false
                           :objectId            (get-object-id this)}})
           (unwrap page)))
    (catch DevtoolsProtocolException ex
      (when (and (= -32000 (ex-code ex))
                 (= "Cannot find context with specified id" (ex-message ex)))
        (throw (StaleObjectException.)))
      (throw ex))))

(defn call-sync [opts]
  (call* opts {:async? false}))

(defn call-async [opts]
  (call* opts {:async? true}))

(defn get-window [page]
  (eval-sync {:page page :code "return window"}))

(defn scroll-into-view-if-needed [element]
  {:pre [(js-object? element)]}
  (call-async {:code "return __CUIC__.scrollIntoView(this)"
               :this element}))
