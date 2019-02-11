(ns cuic.repl
  (:require [clojure.pprint :refer [pprint]]
            [cuic.core :as c]
            [cuic.impl.dom-node :refer [node?]]
            [cuic.impl.js-bridge :as js]
            [cuic.impl.browser :as browser]))

(defn set-browser!
  "Forcefully sets the default browser to the given one so that every core
   function is run against this browser."
  [browser]
  (println "Forcefully changing the default browser. I hope you know what you're doing... ;-)")
  (alter-var-root #'c/*browser* (constantly browser))
  nil)

(defn swap-config!
  "Forcefully updates the default configurations with the given function."
  [f & args]
  (println "Forcefully changing the default configuration. I hope you know what you're doing... ;-)")
  (apply alter-var-root (concat [#'c/*config* f] args))
  (println "*** New config ***")
  (pprint c/*config*)
  nil)

(def default-launch-opts
  {:headless             false
   :additional-arguments {"window-position"             "0,35"
                          "auto-open-devtools-for-tabs" "true"}})

(defn disable-cache!
  "Disables cache from the given browser instance"
  [browser]
  (-> (browser/tools browser)
      (.getNetwork)
      (.setCacheDisabled true)))

(defn launch-as-default!
  "Launches a new (visible) browser and sets it to the default browser."
  ([opts]
   (doto (c/launch! (merge default-launch-opts opts))
     (disable-cache!)
     (set-browser!)))
  ([] (launch-as-default! {})))

(defn inspect
  "Logs the given DOM nodes to then opened browser's console"
  [nodes]
  {:pre [(every? node? nodes)]}
  (doseq [n nodes]
    (js/exec-in n "console.log('\uD83D\uDC26\uD83D\uDD0D', this)")))
