(ns cuic.impl.browser
  (:require [clojure.string :as string]
            [clojure.tools.logging :refer [trace debug error info]]
            [cuic.impl.activity-tracking :as tracking]
            [clojure.java.io :as io])
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

(defn- build-args [{:keys [headless window-size] :or {headless true} :as opts}]
  (letfn [(with-arg [builder [key val]]
            (if-let [m (->> (seq (.getMethods ChromeArguments$Builder))
                            (filter #(and (= (camelize key) (.getName %))
                                          (= 1 (count (.getParameterTypes %)))))
                            (first))]
              (.invoke m builder (into-array Object [val]))
              builder))]
    (as-> (dissoc opts :headless :window-size) $
          (if-let [{:keys [width height]} window-size]
            (update $ :additional-arguments #(assoc % "window-size" (str width "," height)))
            $)
          (reduce with-arg (ChromeArguments/defaults headless) $)
          (.build $))))

(defn- get-tab [browser]
  (or (some-> browser (.-tab))
      (throw (IllegalStateException. "Browser is not open anymore"))))

(defn- inject-runtime-deps! [^ChromeDevToolsService tools]
  (debug "Enable internal runtime JavaScript dependencies")
  (let [src (slurp (io/resource "js_deps/deps.js"))]
    (-> (.getPage tools)
        (.addScriptToEvaluateOnNewDocument src))))

(defn- init-tab! [chrome-service chrome-tab]
  (let [devtools (.createDevToolsService chrome-service chrome-tab)
        tracking (tracking/init! devtools)]
    (inject-runtime-deps! devtools)
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
  (.-devtools (get-tab browser)))

(defn activities [browser]
  (-> (get-tab browser)
      (.-tracking)
      (tracking/activities)))
