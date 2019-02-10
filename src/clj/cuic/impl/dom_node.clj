(ns cuic.impl.dom-node
  (:require [clojure.string :as string]
            [cuic.impl.exception :as ex :refer [call-node]]
            [cuic.impl.js-bridge :as js]
            [cuic.impl.browser :refer [tools]])
  (:import (java.io Writer)))

(defn node-id->object-id [browser node-id]
  (-> (call-node #(-> (.getDOM (tools browser))
                      (.resolveNode node-id nil nil)))
      (.getObjectId)))

(defn node-id [{:keys [id browser]}]
  {:pre [(some? browser) (some? id)]}
  (-> (.getDOM (tools browser))
      (.requestNode id)))

(defrecord DOMNode [id browser]
  Object
  (toString [this]
    (try
      (let [attrs (->> (call-node #(-> (.getDOM (tools browser))
                                       (.getAttributes (node-id this))))
                       (partition 2 2)
                       (map (fn [[k v]] [(keyword k) v]))
                       (into {}))
            cls   (as-> (get attrs :class "") $
                        (string/split $ #"\s+")
                        (map string/trim $)
                        (remove string/blank? $)
                        (set $)
                        (sort $))
            id    (some->> (:id attrs) (string/trim) (str "#"))
            tag   (some-> (js/eval-in this "this.tagName") (string/lower-case))
            s     (str tag id (string/join "." (cons "" cls)))]
        (if (empty? s)
          (str "<!node id=" id ">")
          (str "<" s ">")))
      (catch Exception e
        (str "<!!error \"" (.getMessage e) "\">")))))

(defmethod print-method DOMNode [node ^Writer w]
  (.write w (.toString node)))

(defn node? [x]
  (instance? DOMNode x))

(defn maybe [x]
  (condp #(%1 %2) x
    nil? nil
    fn? (maybe (x))
    node? x
    sequential?
    (case (count x)
      0 nil
      1 (maybe (first x))
      (throw (ex/retryable (str "Expected exactly 1 node but got " (count x) " instead"))))
    (throw (ex/retryable (str "Value is not a valid DOM node: " (pr-str x))))))

(defn existing [x]
  (condp #(%1 %2) x
    nil? nil
    fn? (existing (x))
    node? x
    sequential?
    (case (count x)
      0 (throw (ex/retryable "Node not found from DOM"))
      1 (maybe (first x))
      (throw (ex/retryable (str "Expected exactly 1 node but got " (count x) " instead"))))
    (throw (ex/retryable (str "Value is not a valid DOM node: " (pr-str x))))))
