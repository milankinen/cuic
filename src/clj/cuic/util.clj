(ns cuic.util
  (:require [cuic.impl.exception :refer [retryable-ex protocol-ex]]
            [cuic.impl.util :refer [scroll-into-view! bounding-box]]
            [cuic.impl.js-bridge :refer [eval-in]]
            [cuic.impl.dom-node :refer [node?]])
  (:import (com.github.kklisura.cdt.services.exceptions ChromeDevToolsInvocationException)))

(defn- normalize [node-or-nodes]
  (->> (if (or (sequential? node-or-nodes)
               (set? node-or-nodes))
         node-or-nodes
         [node-or-nodes])
       (filter node?)))

(defmacro run
  "Helper macro to run custom queries requiring direct DevTools access 
  (see `cuic.core/dev-tools`). Usually you shouldn't need this if you're
  using and combining functions from `cuic.core`.
  
  To see usage, take a look at e.g. `cuic.core/options` implementation."
  [& body]
  `(try
     ~@body
     (catch ChromeDevToolsInvocationException e#
       (if (not= -32000 (.getCode e#))                       ; = "stale node"
         (throw (protocol-ex e#))))))

(defmacro mutation
  "Helper macro to run custom mutation requiring direct DevTools access 
  (see `cuic.core/dev-tools`). Usually you shouldn't need this if you're
  using and mutations from `cuic.core`.
  
  To see usage, take a look at e.g. `cuic.core/focus!` implementation."
  [& body]
  `(when-not (run (do ~@body true))
     (throw (retryable-ex "Operation did not succeed"))))

(defn one
  "Checks that the given node or vector of nodes contains exactly one
   valid DOM node and returns it. Otherwise throws an exception."
  [nodes]
  (let [nodes' (normalize nodes)]
    (if (= (count nodes') 1)
      (first nodes')
      (throw (retryable-ex "Expected exactly one valid DOM node but got " (count nodes') " instead")))))

(defn one-visible
  "Checks that the given node or vector of nodes contains exactly one
   valid DOM node and returns it. Otherwise throws an exception. Also requires
   that the node is visible and throws an exception if this requirement is
   not fulfilled."
  [node]
  (let [n (one node)]
    (when-not (eval-in n "!!this.offsetParent")
      (throw (retryable-ex "The given node is not visible")))
    n))

(defn at-most-one
  "Checks that the given node or vector of nodes contains one valid DOM node
   and returns it. If there is no valid DOM nodes, then returns `nil`.
   Otherwise throws an exception."
  [nodes]
  (let [nodes' (normalize nodes)]
    (if (<= (count nodes') 1)
      (first nodes')
      (throw (retryable-ex "Expected zero or one valid DOM node but got " (count nodes') " instead")))))
