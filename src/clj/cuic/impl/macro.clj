(ns cuic.impl.macro
  (:require [cuic.impl.dom-node :refer [maybe existing]]
            [cuic.impl.exception :as ex]
            [cuic.impl.js-bridge :as js])
  (:import (cuic ExecutionException)))

(defmacro ignore-stale [expr]
  `(try
     ~expr
     (catch ExecutionException e#
       (if-not (ex/stale-node? e#) (throw e#))
       nil)))

(defmacro let-some
  [[binding expr] & body]
  `(if-let [~binding (maybe ~expr)]
     ~@body))

(defmacro let-existing
  {:style/indent 0}
  [[binding expr] & body]
  {:pre [(symbol? binding)]}
  `(let [~binding (existing ~expr)]
     ~@body))

(defmacro let-visible
  {:style/indent 0}
  [[binding expr] & body]
  {:pre [(symbol? binding)]}
  `(let [~binding (existing ~expr)]
     (if-not (js/eval-in ~binding "!!this.offsetParent")
       (throw (ex/retryable "Node is not visible")))
     ~@body))
