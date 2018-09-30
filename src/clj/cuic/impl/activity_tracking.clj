(ns cuic.impl.activity-tracking
  (:require [clojure.string :as string]
            [clojure.tools.logging :refer [debug info error]])
  (:import (java.io Closeable)
           (com.github.kklisura.cdt.services ChromeDevToolsService)
           (com.github.kklisura.cdt.protocol.support.types EventHandler EventListener)
           (com.github.kklisura.cdt.protocol.events.page FrameStartedLoading)
           (com.github.kklisura.cdt.protocol.events.network RequestWillBeSent ResponseReceived)))

(defn- unsubscribe-all! [listeners]
  (doseq [^EventListener li listeners]
    (.off li)))

(defn- request-will-be-sent [activities ^RequestWillBeSent ev]
  (let [request-id (.getRequestId ev)
        request    {:task-type    :http-request
                    :request-id   request-id
                    :request-type (keyword (string/lower-case (.getType ev)))
                    :url          (.getUrl (.getRequest ev))
                    :method       (.getMethod (.getRequest ev))
                    :frame-id     (.getFrameId ev)}]
    (debug "Recorded activity - HTTP Request will be sent" request)
    (assoc activities [:http-request request-id] request)))

(defn- response-received [activities ^ResponseReceived ev]
  (debug "Recorded activity - HTTP response received, request-id =" (.getRequestId ev))
  (dissoc activities [:http-request (.getRequestId ev)]))

(defn- frame-started-loading [activities ^FrameStartedLoading event]
  ; frame reloadedD - clear previous requests
  (let [frame-id (.getFrameId event)]
    (debug "Recorded activity - frame started loading, frame-id =" frame-id)
    (->> (remove #(= frame-id (:frame-id (second %))) activities)
         (into {}))))

(defn- handler [activities-atom handler-fn]
  (reify EventHandler
    (onEvent [_ ev]
      (try
        (swap! activities-atom handler-fn ev)
        (catch Exception ex
          (.printStackTrace ex)
          (error ex "Activity tracking error"))))))

(deftype ActivityTracker [activities listeners]
  Closeable
  (close [_]
    (swap! listeners unsubscribe-all!)))

(defn init! [^ChromeDevToolsService tools]
  (let [activities (atom {})
        listeners  (atom [])
        page       (.getPage tools)
        network    (.getNetwork tools)]
    (try
      (.enable page)
      (.enable network)
      (swap! listeners conj (.onFrameStartedLoading page (handler activities frame-started-loading)))
      (swap! listeners conj (.onRequestWillBeSent network (handler activities request-will-be-sent)))
      (swap! listeners conj (.onResponseReceived network (handler activities response-received)))
      (debug "Activity tracking started")
      (ActivityTracker. activities listeners)
      (catch Exception e
        (unsubscribe-all! listeners)
        (throw e)))))

(defn activities [^ActivityTracker tracker]
  (vals @(.-activities tracker)))
