(ns cuic.impl.dom-node
  (:require [clojure.string :as string]
            [cuic.impl.js-bridge :as js]
            [cuic.impl.browser :refer [tools]])
  (:import (java.io Writer)))

(declare node->node-id)

(defrecord DOMNode [id browser type]
  Object
  (toString [this]
    (if (= :document type)
      (str "<DOCUMENT>")
      (try
        (let [dom   (.getDOM (tools browser))
              attrs (->> (.getAttributes dom (node->node-id this))
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
          (str "<ERROR \"" (.getMessage e) "\">"))))))

(defmethod print-method DOMNode [node ^Writer w]
  (.write w (.toString node)))

(defn node? [x]
  (instance? DOMNode x))

(defn node-id->node [node-id type browser]
  (let [dom (.getDOM (tools browser))
        id  (.getObjectId (.resolveNode dom node-id nil nil))]
    (->DOMNode id browser type)))

(defn node->node-id [{:keys [id browser]}]
  {:pre [(some? browser) (some? id)]}
  (-> (.getDOM (tools browser))
      (.requestNode id)))

(defn document? [node]
  (and (node? node)
       (= :document (:type node))))
