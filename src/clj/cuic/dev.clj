(ns cuic.dev
  (:require [clojure.pprint :refer [pprint]]
            [cuic.core :as core]))

(defn set-browser! [browser]
  (println "Forcefully changing the default browser. I hope you know what you're doing... ;-)")
  (alter-var-root #'core/*browser* (constantly browser))
  nil)

(defn update-config! [f & args]
  (println "Forcefully changing the default configuration. I hope you know what you're doing... ;-)")
  (apply alter-var-root (concat [#'core/*config* f] args))
  (println "*** New config ***")
  (pprint core/*config*)
  nil)
