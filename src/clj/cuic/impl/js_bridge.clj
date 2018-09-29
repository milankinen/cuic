(ns cuic.impl.js-bridge
  (:refer-clojure :exclude [eval])
  (:require [clojure.string :as string]
            [clojure.tools.logging :refer [debug]]
            [clj-chrome-devtools.commands.runtime :as runtime]
            [clj-chrome-devtools.commands.dom :as dom]
            [cuic.impl.ws-invocation :refer [call]]
            [cuic.impl.exception :as ex]
            [cuic.impl.browser :refer [c]]))

(defn- handle-js-result [res]
  (if (= "error" (:subtype (:result res)))
    (throw (ex/js-error (:description (:result res)))))
  (:value (:result res)))

(defn node-id->object-id [browser node-id]
  (-> (call dom/resolve-node (c browser) {:node-id node-id})
      (get-in [:object :object-id])))

(defn eval [browser expr & [command-line-api?]]
  (-> (call runtime/evaluate
            (c browser)
            {:expression               (str "(async function(){ return " expr "})()")
             :return-by-value          true
             :include-command-line-api (true? command-line-api?)
             :await-promise            true})
      (handle-js-result)))

(defn exec-in [{:keys [id browser] :as node} body & args]
  (when node
    (-> (call runtime/call-function-on
              (c browser)
              {:object-id            (node-id->object-id browser id)
               :return-by-value      true
               :function-declaration (str "async function("
                                          (string/join "," (map first args))
                                          "){ " body " }")
               :arguments            (mapv (fn [[_ val]] {:value val}) args)
               :await-promise        true})
        (handle-js-result))))

(defn eval-in [node expr]
  (exec-in node (str "return " expr)))
