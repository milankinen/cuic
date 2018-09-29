(ns cuic.impl.html
  (:require [clojure.string :as string])
  (:import (org.jsoup Jsoup)
           (org.jsoup.nodes Document Attributes Attribute BooleanAttribute Comment Element TextNode XmlDeclaration DataNode)))

(defmulti ->clj type)

(defmethod ->clj BooleanAttribute [_]
  true)

(defmethod ->clj Attribute [a]
  (.getValue a))

(defmethod ->clj Attributes [attrs]
  (->> (seq (.asList attrs))
       (map (fn [a] [(keyword (.getKey a)) (->clj a)]))
       (into {})))

(defmethod ->clj Element [elem]
  (-> (concat
        [(keyword (.nodeName elem))
         (->clj (.attributes elem))]
        (->> (.childNodes elem)
             (map ->clj)
             (remove #(and (string? %) (string/blank? %)))
             (vec)))
      (vec)))

(defmethod ->clj TextNode [node]
  (.text node))

(defmethod ->clj Comment [comment]
  [:-#comment (.getData comment)])

(defmethod ->clj DataNode [node]
  [:-#data (.getWholeData node)])

(defmethod ->clj XmlDeclaration [decl]
  [:-#declaration (.getWholeDeclaration decl)])

(defmethod ->clj Document [doc]
  (->clj (.child doc 0)))

(defmethod ->clj :default [x]
  (throw (IllegalStateException. (str "Missing parser: " (type x)))))

(defn- parse-doc ^Document [html]
  (Jsoup/parse html))

(defn- parse-body [html]
  (-> (parse-doc html)
      (.body)))

(defn- parse-head [html]
  (-> (parse-doc html)
      (.head)))

(defn- parse-elem [html]
  (-> (Jsoup/parseBodyFragment html)
      (.body)
      (.child 0)))

(defn parse [document? ^String html]
  (-> (cond
        document? (parse-doc html)
        (re-find #"^<body(\s|>)" html) (parse-body html)
        (re-find #"^<head(\s|>)" html) (parse-head html)
        :else (parse-elem html))
      (->clj)))