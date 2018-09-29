(ns cuic.impl.dom-node
  (:require [clojure.string :as string]
            [clj-chrome-devtools.commands.dom :as dom]
            [cuic.impl.ws-invocation :refer [call]]
            [cuic.impl.exception :as ex :refer [safely]]
            [cuic.impl.browser :refer [c]]
            [cuic.impl.js-bridge :as js])
  (:import (clojure.lang IPersistentVector)
           (java.io Writer)))

(defrecord DOMNode [id browser]
  Object
  (toString [this]
    (try
      (let [attrs (->> (call dom/get-attributes (c browser) {:node-id id})
                       (:attributes)
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
          (str "<NODE id=" id ">")
          (str "<" s ">")))
      (catch Exception e
        (str "<NODE id=" id " error=" (.getMessage e) ">")))))

(defmethod print-method DOMNode [node ^Writer w]
  (.write w (.toString node)))

(defmulti maybe type)
(defmethod maybe nil [_]
  nil)
(defmethod maybe DOMNode [n]
  (and (safely (js/eval-in n "!!this"))
       n))
(defmethod maybe IPersistentVector [xs]
  (if (<= (count xs) 1)
    (maybe (first xs))
    (throw (ex/retryable "Node list can't contain more than one node" {:list (vec xs)}))))
(defmethod maybe :default [x]
  (if (sequential? x)
    (maybe (vec x))
    (throw (ex/retryable "Value is not a valid DOM node" {:value x}))))

(defmulti existing type)
(defmethod existing DOMNode [n]
  (if-not (safely (js/eval-in n "!!this"))
    (throw (ex/stale-node))
    n))
(defmethod existing IPersistentVector [xs]
  (case (count xs)
    1 (existing (first xs))
    0 (throw (ex/retryable "Node list can't be empty"))
    (throw (ex/retryable "Node list must contain exactly one node" {:list (vec xs)}))))
(defmethod existing :default [x]
  (if (sequential? x)
    (existing (vec x))
    (throw (throw (ex/retryable "Value is not a valid DOM node" {:value x})))))

(defmulti visible type)
(defmethod visible DOMNode [n]
  (case (safely (js/eval-in n "!!this.offsetParent"))
    nil (throw (ex/stale-node))
    false (throw (ex/node-not-visible))
    n))
(defmethod visible IPersistentVector [xs]
  (case (count xs)
    1 (visible (first xs))
    0 (throw (ex/retryable "Node list can't be empty"))
    (throw (ex/retryable "Node list must contain exactly one node" {:list (vec xs)}))))
(defmethod visible :default [x]
  (if (sequential? x)
    (visible (vec x))
    (throw (ex/retryable "Value is not a valid DOM node" {:value x}))))

