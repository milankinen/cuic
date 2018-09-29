(ns cuic.impl.browser
  (:require [clojure.string :as string]
            [clojure.tools.logging :refer [trace debug error info]]
            [clj-chrome-devtools.automation :refer [create-automation]]
            [clj-chrome-devtools.impl.connection :as connection]
            [clj-chrome-devtools.commands.page :as page]
            [cuic.impl.ws-invocation :refer [call]]
            [cuic.impl.task-tracking :as tracking]
            [clojure.java.io :as io])
  (:import (java.io Closeable IOException InputStream)
           (java.util.concurrent TimeUnit)
           (java.net Socket)
           (java.util Scanner)
           (cuic.impl.session Session)))

(defn- port-available? [port]
  (try
    (with-open [_ (Socket. "localhost" port)]
      false)
    (catch IOException _
      true)))

(defn- trace-std! [^InputStream is]
  (letfn [(capture []
            (let [scanner (Scanner. is)]
              (while (and (not (Thread/interrupted))
                          (.hasNextLine scanner))
                (trace (.nextLine scanner)))))]
    (doto (Thread. capture)
      (.setDaemon true)
      (.start))))

(defn- close-process! [proc]
  (when proc
    (.destroy proc)
    (if-not (.waitFor proc 5 TimeUnit/SECONDS)
      (.destroyForcibly proc))
    nil))

(defn- open-ws-connection! [port]
  (debug "Open Websocket connection to RDP port" port)
  (connection/connect "localhost" port 10000))

(defn- close-ws-connection! [conn]
  (when conn
    (debug "Closing Websocket connection")
    (try (.close conn) (catch Exception _))
    nil))

(defn- add-internal-js-deps! [conn]
  (debug "Enable internal JS deps")
  (let [src (slurp (io/resource "js_deps/deps.js"))]
    (call page/add-script-to-evaluate-on-new-document conn {:source src})))

; ====

(defprotocol Browser
  (c [_])
  (options [_])
  (session [_])
  (tracking [_]))

(defn open! [session {:keys [rdport headless gpu]
                      :as   options}]
  {:pre [(instance? Session session)]}
  (let [port (or rdport (first (filter port-available? (map #(+ 9222 %) (range)))))
        proc (atom nil)
        conn (atom nil)]
    (try
      (let [args (->> [(:executable session)
                       "--no-first-run"
                       (str "--remote-debugging-port=" port)
                       (when-let [dir (:directory session)]
                         (str "--user-data-dir=" (.getAbsolutePath dir)))
                       (when-not gpu "--disable-gpu")
                       (when headless "--headless")
                       (when-let [{:keys [width height]} (:window-size session)]
                         (str "--window-size=" width "," height))
                       "about:blank"]
                      (filter some?)
                      (into-array String))]
        (debug "Open browser, args:" (string/join " " args))
        (reset! proc (.exec (Runtime/getRuntime) args))
        #_(trace-std! (.getInputStream @proc))
        #_(trace-std! (.getErrorStream @proc))
        (reset! conn (open-ws-connection! port))
        (add-internal-js-deps! @conn)
        (let [tracking (tracking/start! @conn)]
          (debug "Browser ready")
          (reify
            Browser
            (c [_]
              (or @conn (throw (IllegalStateException. "Browser is not open anymore"))))
            (options [_]
              options)
            (session [_]
              session)
            (tracking [_]
              tracking)
            Closeable
            (close [_]
              (.close tracking)
              (swap! conn close-ws-connection!)
              (swap! proc close-process!)))))
      (catch Exception e
        (swap! proc close-process!)
        (throw e)))))
