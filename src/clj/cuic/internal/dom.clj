(ns ^:no-doc cuic.internal.dom
  (:require [cuic.internal.cdt :refer [invoke ex-code]]
            [clojure.string :as string])
  (:import (java.io Writer)
           (cuic.internal StaleNodeException)))

(set! *warn-on-reflection* true)

(defn- stale-node? [ex]
  (and (= -32000 (ex-code ex))
       (contains? #{"No node with given id found"
                    "Node with given id does not belong to the document"}
                  (ex-message ex))))

(defn- resolve-object-id [cdt {:keys [nodeId backendNodeId]}]
  (try
    (when-let [args (cond
                      backendNodeId {:backendNodeId backendNodeId}
                      nodeId {:nodeId nodeId}
                      :else nil)]
      (-> (invoke {:cdt  cdt
                   :cmd  "DOM.resolveNode"
                   :args args})
          (get-in [:object :objectId])))
    (catch Exception ex
      (if (stale-node? ex)
        (throw (StaleNodeException.))
        (throw ex)))))

(defn- check-in-dom [cdt object-id]
  (try
    (when-not (-> (invoke {:cdt  cdt
                           :cmd  "Runtime.callFunctionOn"
                           :args {:functionDeclaration "function() { return this === document || !!this.parentNode; }"
                                  :awaitPromise        false
                                  :returnByValue       true
                                  :objectId            object-id}})
                  (get-in [:result :value])
                  (boolean))
      (throw (StaleNodeException.)))
    (catch StaleNodeException ex
      (throw ex))
    (catch Exception ex
      (if (stale-node? ex)
        (throw (StaleNodeException.))
        (throw ex)))))

(defn- resolve-node-id [cdt object-id]
  (try
    (let [node (invoke {:cdt  cdt
                        :cmd  "DOM.requestNode"
                        :args {:objectId object-id}})]
      (:nodeId node))
    (catch Exception ex
      (if (stale-node? ex)
        (throw (StaleNodeException.))
        (throw ex)))))

(defn- resolve-tag [{:keys [cdt object-id]}]
  (let [desc (invoke {:cdt  cdt
                      :cmd  "DOM.describeNode"
                      :args {:objectId object-id}})
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
           (str "." (string/join "." cls))))))

(defrecord Element [cdt object-id custom-name selector])

(defmethod print-method Element [{:keys [custom-name selector] :as node} writer]
  (let [base-props
        (try
          {:tag (resolve-tag node)}
          (catch StaleNodeException _
            {:stale true})
          (catch Exception ex
            (if (= -32000 (ex-code ex))
              {:stale true}
              {:error (ex-message ex)})))
        print-props
        (cond-> base-props
                custom-name (assoc :name custom-name)
                selector (assoc :selector selector))]
    (.write ^Writer writer ^String (str "#element " (pr-str print-props)))))


;;
;;

(defmacro stale-as-nil [& body]
  `(try
     ~@body
     (catch StaleNodeException ex#)))

(defmacro stale-as-ex [ex & body]
  `(try
     ~@body
     (catch StaleNodeException ex#
       (throw ~ex))))

(defn element?
  "Returns boolean whether the given value is valid remote
   cuic html element or not"
  [val]
  (instance? Element val))

(defn wrap-element
  "Creates a new remote html element wrapper, referenced by element's
   backend node id"
  [cdt {:keys [nodeId backendNodeId objectId] :as lookup} context custom-name selector]
  {:pre [(some? cdt)
         (or (element? context)
             (nil? context))]}
  (cond
    (some? objectId)
    (let [sel (when-let [parts (seq (filter some? [(:selector context) selector]))]
                (string/join " " parts))]
      (->Element cdt objectId custom-name sel))
    (or (some? backendNodeId)
        (some? nodeId))
    (recur cdt {:objectId (resolve-object-id cdt lookup)} context custom-name selector)
    :else nil))

(defn get-object-id [{:keys [cdt object-id] :as elem} {:keys [dom?]}]
  {:pre [(element? elem)]}
  (when dom?
    (check-in-dom cdt object-id))
  (:object-id elem))

(defn get-node-id
  ([elem] (get-node-id elem {:dom? true}))
  ([{:keys [cdt object-id] :as elem} {:keys [dom?]}]
   {:pre [(element? elem)]}
   (when dom?
     (check-in-dom cdt object-id))
   (resolve-node-id cdt object-id)))

(defn get-element-name [elem]
  {:pre [(element? elem)]}
  (or (:custom-name elem)
      (:selector elem)
      (try (resolve-tag elem) (catch Exception _))
      "<unknown>"))

(defn assoc-custom-name [elem name]
  {:pre [(element? elem)]}
  (assoc elem :custom-name name))

(defn get-custom-name [elem]
  {:pre [(element? elem)]}
  (:custom-name elem))

(defn get-element-cdt [elem]
  {:pre [(element? elem)]}
  (:cdt elem))
