(ns cuic.browser
  (:require [clojure.spec.alpha :as s]
            [cuic.impl.browser :as impl])
  (:import (java.io Closeable)
           (cuic.impl.browser Browser)))

(defn launch!
  "Launches a Chrome browser instance by using the given options. The following options
   and defaults will be used:
```
{:headless                               true
 :window-size                            nil
 :disable-gpu                            true
 :remote-debugging-port                  0
 :disable-hang-monitor                   true
 :disable-popup-blocking                 true
 :disable-prompt-on-repost               true
 :safebrowsing-disable-auto-update       true
 :no-first-run                           true
 :disable-sync                           true
 :metrics-recording-only                 true
 :user-data-dir                          nil
 :disable-extensions                     true
 :disable-client-side-phishing-detection true
 :incognito                              false
 :disable-default-apps                   true
 :disable-background-timer-throttling    true
 :disable-background-networking          true
 :no-default-browser-check               true
 :additional-arguments                   {}
 :mute-audio                             true
 :disable-translate                      true
 :hide-scrollbars                        true}
```
"
  ([opts]
   (assert (s/valid? impl/opts-spec opts))
   (impl/launch! (merge impl/opts-defaults opts)))
  ([] (launch! {:headless true})))

(defn close!
  "Closes the given browser"
  [^Closeable browser]
  {:pre [(instance? Browser browser)]}
  (.close browser))
