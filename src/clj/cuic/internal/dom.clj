(ns ^:no-doc cuic.internal.dom
  (:require [cuic.internal.cdt :refer [invoke ex-code]]
            [cuic.internal.page :refer [get-page-cdt]]
            [cuic.internal.runtime :refer [js-object? get-object-page get-remote-object js-object->tagged-literal]]
            [clojure.string :as string])
  (:import (cuic.internal StaleObjectException)))

(set! *warn-on-reflection* true)

(defn- stale-node? [ex]
  (and (= -32000 (ex-code ex))
       (contains? #{"No node with given id found"
                    "Node with given id does not belong to the document"}
                  (ex-message ex))))

(defn- resolve-tag [elem]
  (or (:description (get-remote-object elem))
      (let [desc (invoke {:cdt  (get-page-cdt (get-object-page elem))
                          :cmd  "DOM.describeNode"
                          :args {:objectId (get-remote-object elem)}})
            node (:node desc)
            tag-name (string/lower-case (:nodeName node))
            attrs (into {} (map vec (partition 2 2 (:attributes node))))]
        (str tag-name
             (when-let [id (get attrs "id")]
               (str "#" id))
             (when-let [cls (->> (string/split (get attrs "class" "") #" ")
                                 (remove string/blank?)
                                 (map string/trim)
                                 (seq))]
               (str "." (string/join "." cls)))))))

(defmethod js-object->tagged-literal "node" [elem]
  (if (= "HTMLDocument" (:className (get-remote-object elem)))
    "#document {}"
    (let [custom-name (:custom-name (meta elem))
          selector (:selector (meta elem))
          base-props (try
                       {:tag (resolve-tag elem)}
                       (catch StaleObjectException _
                         {:stale true})
                       (catch Exception ex
                         (if (= -32000 (ex-code ex))
                           {:stale true}
                           {:error (ex-message ex)})))
          print-props (cond-> base-props
                              custom-name (assoc :name custom-name)
                              selector (assoc :selector selector))]
      (str "#element " (pr-str print-props)))))

;;
;;

(defn element? [val]
  (and (js-object? val)
       (= "node" (:subtype (get-remote-object val)))))

(defn document? [val]
  (and (js-object? val)
       (= "HTMLDocument" (:className (get-remote-object val)))))

(defn with-element-meta
  [js-object
   context
   custom-name
   selector]
  {:pre [(js-object? js-object)
         (or (element? context)
             (nil? context))]}
  (vary-meta js-object assoc
             :selector (when-let [parts (seq (filter some? [(:selector context) selector]))]
                         (string/join " " parts))
             :custom-name custom-name))

(defn get-node-id [elem]
  {:pre [(element? elem)]}
  (try
    (let [cdt (get-page-cdt (get-object-page elem))
          object-id (:objectId (get-remote-object elem))
          node (invoke {:cdt  cdt
                        :cmd  "DOM.requestNode"
                        :args {:objectId object-id}})]
      (:nodeId node))
    (catch Exception ex
      (if (stale-node? ex)
        (throw (StaleObjectException.))
        (throw ex)))))

(defn get-element-name [elem]
  {:pre [(element? elem)]}
  (or (:custom-name (meta elem))
      (:selector (meta elem))
      (try (resolve-tag elem) (catch Exception _))
      "<unknown>"))

(defn assoc-custom-name [elem name]
  {:pre [(element? elem)]}
  (vary-meta elem assoc :custom-name name))

(defn get-custom-name [elem]
  {:pre [(element? elem)]}
  (:custom-name (meta elem)))

(defn get-element-cdt [elem]
  {:pre [(element? elem)]}
  (get-page-cdt (get-object-page elem)))

