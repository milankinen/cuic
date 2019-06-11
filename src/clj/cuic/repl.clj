(ns cuic.repl
  (:require [clojure.pprint :refer [pprint]]
            [cuic.core :as c]
            [cuic.browser :refer [launch!]]
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

(defn set-typing-speed!
  "Sets the new default typing speed for REPL"
  [new-speed]
  {:pre [(or (integer? new-speed)
             (contains? #{:slow :normal :fast :tycitys} new-speed))]}
  (alter-var-root #'c/*typing-speed* (constantly new-speed)))

(defn set-timeout!
  "Sets the new default wait timeout for REPL"
  [new-timeout]
  {:pre [(and (integer? new-timeout)
              (pos? new-timeout))]}
  (alter-var-root #'c/*timeout* (constantly new-timeout)))

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

(defn launch-dev-browser!
  "Launches a new (visible) browser and sets it to the default browser."
  ([opts]
   (doto (launch! (merge default-launch-opts opts))
     (disable-cache!)
     (set-browser!)))
  ([] (launch-dev-browser! {})))

(defn inspect
  "Logs the given DOM nodes to then opened browser's console"
  [nodes]
  {:pre [(every? node? nodes)]}
  (doseq [n nodes]
    (js/exec-in n "console.log('\uD83D\uDC26\uD83D\uDD0D', this)")))
