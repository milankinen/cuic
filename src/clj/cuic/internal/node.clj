(ns ^:no-doc cuic.internal.node
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

(defrecord Node [cdt object-id display-name selector])

(defmethod print-method Node [{:keys [display-name selector] :as node} writer]
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
                display-name (assoc :name display-name)
                selector (assoc :selector selector))]
    (.write ^Writer writer ^String (str "#node " (pr-str print-props)))))


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

(defn node?
  "Returns boolean whether the given value is valid remote
   node or not"
  [val]
  (instance? Node val))

(defn maybe-node? [val]
  (or (node? val)
      (nil? val)))

(defn wrap-node
  "Creates a new remote node wrapper, referenced by node's
   backend node id"
  [cdt {:keys [nodeId backendNodeId objectId] :as lookup} context display-name selector]
  {:pre [(some? cdt)
         (or (node? context)
             (nil? context))]}
  (cond
    (some? objectId)
    (let [sel (when-let [parts (seq (filter some? [(:selector context) selector]))]
                (string/join " " parts))]
      (->Node cdt objectId display-name sel))
    (or (some? backendNodeId)
        (some? nodeId))
    (recur cdt {:objectId (resolve-object-id cdt lookup)} context display-name selector)
    :else nil))

(defn get-object-id [{:keys [cdt object-id] :as node}]
  {:pre [(node? node)]}
  (check-in-dom cdt object-id)
  (:object-id node))

(defn get-node-id [{:keys [cdt object-id] :as node}]
  {:pre [(node? node)]}
  (check-in-dom cdt object-id)
  (resolve-node-id cdt object-id))

(defn get-node-name [node]
  {:pre [(node? node)]}
  (or (:display-name node)
      (:selector node)
      (try (resolve-tag node) (catch Exception _))))

(defn assoc-display-name [node name]
  {:pre [(node? node)]}
  (assoc node :display-name name))

(defn get-node-display-name [node]
  {:pre [(node? node)]}
  (:display-name node))

(defn get-node-cdt [node]
  (:cdt node))
