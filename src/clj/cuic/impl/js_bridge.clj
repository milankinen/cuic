(ns cuic.impl.js-bridge
  (:refer-clojure :exclude [eval])
  (:require [clojure.string :as string]
            [clojure.tools.logging :refer [debug]]
            [cuic.impl.exception :refer [call-node call] :as ex]
            [cuic.impl.browser :refer [tools]])
  (:import (com.github.kklisura.cdt.protocol.types.runtime RemoteObject CallArgument)
           (java.util Map)))

(defn- cljze [x]
  (condp instance? x
    Map (into {} (map (fn [[k v]] [(keyword k) (cljze v)]) x))
    Iterable (mapv cljze x)
    x))

(defn- call-fn-on [{:keys [id browser]} body args]
  (-> (call-node (-> (.getRuntime (tools browser))
                     (.callFunctionOn (str "async function("
                                           (string/join "," (map first args))
                                           "){ " body " }")
                                      id
                                      (apply list (map (fn [[_ val]] (doto (CallArgument.) (.setValue val))) args))
                                      nil                   ; silent
                                      true                  ; return by value
                                      nil                   ; generate preview
                                      nil                   ; user gesture
                                      true                  ; await promise
                                      nil                   ; execution context id
                                      nil                   ; object group
                                      )))
      (.getResult)))

(defn- wrap-async-expr [expr]
  (str "(async function(){ try { return {value:" expr "} } catch(e) { return {error: e.toString()} } })()"))

(defn- unwrap-async-result [^RemoteObject res]
  (if (or (= "error" (some-> (.getSubtype res) (string/lower-case)))
          (contains? (.getValue res) "error"))
    (throw (ex/js-error (or (.getDescription res) (get (.getValue res) "error"))))
    (get (.getValue res) "value")))

(defn- handle-non-wrapped-result [^RemoteObject res]
  (if (= "error" (some-> (.getSubtype res) (string/lower-case)))
    (throw (ex/js-error (.getDescription res)))
    (.getValue res)))

(defn eval [browser expr & [command-line-api?]]
  (debug "eval expr:" expr)
  (-> (call (-> (.getRuntime (tools browser))
                (.evaluate (wrap-async-expr expr)
                           nil                              ; objectGroup
                           (true? command-line-api?)        ; include command line api
                           nil                              ; silent
                           nil                              ; context id
                           true                             ; return by value
                           nil                              ; generate preview
                           nil                              ; user gesture
                           true                             ; await promise
                           nil                              ; throw on side-effects
                           nil                              ; timeout
                           )))
      (.getResult)
      (unwrap-async-result)
      (cljze)))

(defn exec-in [node body & args]
  (when node
    (debug "exec-in node:" (:id node)
           "\nbody:" body
           "\nargs:" args)
    (-> (call-fn-on node body args)
        (handle-non-wrapped-result)
        (cljze))))

(defn eval-in [node expr]
  (when node
    (debug "eval-in node:" (:id node)
           "\nexpr:" expr)
    (-> (call-fn-on node (str "return " expr) [])
        (handle-non-wrapped-result)
        (cljze))))
