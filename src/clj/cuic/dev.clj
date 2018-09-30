(ns cuic.dev
  (:require [clojure.pprint :refer [pprint]]
            [cuic.core :as c]))

(defn set-browser!
  "Forcefully sets the default browser to the given one so that every core
   function is run against this browser."
  [browser]
  (println "Forcefully changing the default browser. I hope you know what you're doing... ;-)")
  (alter-var-root #'c/*browser* (constantly browser))
  nil)

(defn update-config!
  "Forcefully updates the default configurations with the given function."
  [f & args]
  (println "Forcefully changing the default configuration. I hope you know what you're doing... ;-)")
  (apply alter-var-root (concat [#'c/*config* f] args))
  (println "*** New config ***")
  (pprint c/*config*)
  nil)

(defn launch!!
  "Launches a new (visible) browser and sets it to the default browser."
  ([width height headless? opts]
   (doto (c/launch! (merge {:window-size {:width width :height height} :headless headless?} opts))
     (set-browser!)))
  ([width height headless?]
   (launch!! width height headless? {}))
  ([width height]
   (launch!! width height false))
  ([] (doto (c/launch! {:headless false})
        (set-browser!))))
