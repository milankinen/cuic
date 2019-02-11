(ns cuic.impl.browser
  (:require [clojure.string :as string]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging :refer [trace debug error info]]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [cuic.impl.activity-tracking :as tracking])
  (:import (java.io Closeable)
           (com.github.kklisura.cdt.launch ChromeArguments$Builder ChromeArguments ChromeLauncher)
           (com.github.kklisura.cdt.services ChromeDevToolsService ChromeService)
           (com.github.kklisura.cdt.services.types ChromeTab)))

(deftype Tab [^ChromeService service
              ^ChromeTab tab
              ^ChromeDevToolsService devtools
              tracking]
  Closeable
  (close [this]
    (when-some [t (.-tab this)]
      (set! (. this tab) nil)
      (.close tracking)
      (.closeTab service t))))

(deftype Browser [launcher tab]
  Closeable
  (close [this]
    (when-some [l (.-launcher this)]
      (set! (. this launcher) nil)
      (set! (. this tab) nil)
      (.close l))))

(defn- camelize [kw]
  (-> (name kw)
      (string/replace #"-(\w)" (comp string/upper-case last))))

(defn- build-args [{:keys [headless window-size] :as opts}]
  (letfn [(with-arg [builder [key val]]
            (.println System/out (str key " " val))
            (if-let [m (->> (seq (.getMethods ChromeArguments$Builder))
                            (filter #(and (= (camelize key) (.getName %))
                                          (= 1 (count (.getParameterTypes %)))))
                            (first))]
              (let [v (if (integer? val) (int val) val)]
                (.invoke m builder (into-array Object [v])))
              builder))]
    (as-> (dissoc opts :headless :window-size) $
          (if-let [{:keys [width height]} window-size]
            (update $ :additional-arguments #(assoc % "window-size" (str width "," height)))
            $)
          (remove (comp nil? second) $)
          (reduce with-arg (ChromeArguments/defaults headless) $)
          (.build $))))

(defn- get-tab [browser]
  (or (some-> browser (.-tab))
      (throw (IllegalStateException. "Browser is not open anymore"))))

(defn- inject-runtime-deps! [^ChromeDevToolsService tools]
  (debug "Enable internal runtime JavaScript dependencies")
  (let [src (slurp (io/resource "cuic_runtime_deps.js"))]
    (-> (.getPage tools)
        (.addScriptToEvaluateOnNewDocument src))))

(defn- init-tab! [chrome-service chrome-tab]
  (let [devtools (.createDevToolsService chrome-service chrome-tab)
        tracking (tracking/init! devtools)]
    (inject-runtime-deps! devtools)
    (Tab. chrome-service chrome-tab devtools tracking)))

(def opts-defaults
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
   :hide-scrollbars                        true})

(def opt-defaults-str
  (with-out-str (pprint opts-defaults)))

(defn bool? [x]
  (contains? #{true false} x))

(s/def ::headless bool?)
(s/def ::window-size (s/nilable map?))
(s/def ::disable-gpu bool?)
(s/def ::remote-debugging-port integer?)
(s/def ::disable-hang-monitor bool?)
(s/def ::disable-popup-blocking bool?)
(s/def ::disable-prompt-on-repost bool?)
(s/def ::safebrowsing-disable-auto-update bool?)
(s/def ::no-first-run bool?)
(s/def ::disable-sync bool?)
(s/def ::metrics-recording-only bool?)
(s/def ::user-data-dir (s/nilable string?))
(s/def ::disable-extensions bool?)
(s/def ::disable-client-side-phishing-detection bool?)
(s/def ::incognito bool?)
(s/def ::disable-default-apps bool?)
(s/def ::disable-background-timer-throttling bool?)
(s/def ::disable-background-networking bool?)
(s/def ::no-default-browser-check bool?)
(s/def ::additional-arguments (s/map-of string? string?))
(s/def ::mute-audio bool?)
(s/def ::disable-translate bool?)
(s/def ::hide-scrollbars bool?)

(def valid-opt-keys
  (set (keys opts-defaults)))

(def opts-spec
  (s/and
    (s/keys :opt-un
            [::headless
             ::window-size
             ::disable-gpu
             ::remote-debugging-port
             ::disable-hang-monitor
             ::disable-popup-blocking
             ::disable-prompt-on-repost
             ::safebrowsing-disable-auto-update
             ::no-first-run
             ::disable-sync
             ::metrics-recording-only
             ::user-data-dir
             ::disable-extensions
             ::disable-client-side-phishing-detection
             ::incognito
             ::disable-default-apps
             ::disable-background-timer-throttling
             ::disable-background-networking
             ::no-default-browser-check
             ::additional-arguments
             ::mute-audio
             ::disable-translate
             ::hide-scrollbars])
    #(every? (comp valid-opt-keys first) %)))

(defn launch! [options]
  (let [chrome-laucher (ChromeLauncher.)
        chrome-service (.launch chrome-laucher (build-args options))
        chrome-tab     (or (->> (.getTabs chrome-service)
                                (filter #(= "chrome://newtab/" (.getUrl %)))
                                (first))
                           (.createTab chrome-service))]
    (try
      (Browser. chrome-laucher (init-tab! chrome-service chrome-tab))
      (catch Exception e
        (.close chrome-laucher)
        (throw e)))))

(defn tools ^ChromeDevToolsService [browser]
  (.-devtools (get-tab browser)))

(defn tasks [browser]
  (-> (get-tab browser)
      (.-tracking)
      (tracking/activities)))
