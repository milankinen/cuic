(ns cuic.impl.browser
  (:require [clojure.string :as string]
            [clojure.tools.logging :refer [trace debug error info]]
            [cuic.impl.task-tracking :as tracking])
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

(defn- build-args [{:keys [headless] :or {headless true} :as opts}]
  (letfn [(with-arg [builder [key val]]
            (if-let [m (->> (seq (.getMethods ChromeArguments$Builder))
                            (filter #(and (= (camelize key) (.getName %))
                                          (= 1 (count (.getParameterTypes %)))))
                            (first))]
              (.invoke m builder (into-array Object [val]))
              builder))]
    (->> (dissoc opts :headless)
         (reduce with-arg (ChromeArguments/defaults headless))
         (.build))))

(defn- init-tab! [chrome-service chrome-tab]
  (let [devtools (.createDevToolsService chrome-service chrome-tab)
        tracking (tracking/init! devtools)]
    (Tab. chrome-service chrome-tab devtools tracking)))

(defn launch! [options]
  (let [chrome-laucher (ChromeLauncher.)
        chrome-service (.launch chrome-laucher (build-args options))
        chrome-tab     (->> (.getTabs chrome-service)
                            (filter #(= "chrome://newtab/" (.getUrl %)))
                            (first))]
    (try
      (Browser. chrome-laucher (init-tab! chrome-service chrome-tab))
      (catch Exception e
        (.close chrome-laucher)
        (throw e)))))

(defn tools ^ChromeDevToolsService [browser]
  (if-let [tab (some-> browser (.-tab))]
    (.-devtools tab)
    (throw (IllegalStateException. "Browser is not open anymore"))))
