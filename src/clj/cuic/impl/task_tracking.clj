(ns cuic.impl.task-tracking
  (:require [clojure.string :as string]
            [clojure.tools.logging :refer [debug info error]])
  (:import (java.io Closeable)
           (com.github.kklisura.cdt.services ChromeDevToolsService)
           (com.github.kklisura.cdt.protocol.support.types EventHandler EventListener)))

(defn- unsubscribe-all! [listeners]
  (doseq [^EventListener li listeners]
    (.off li)))

(defn- request-will-be-sent [state {:keys [params]}]
  (let [request-id (:request-id params)
        request    {:task-type    :http-request
                    :request-type (keyword (string/lower-case (:type params)))
                    :url          (:url (:request params))
                    :method       (:method (:request params))
                    :frame-id     (:frame-id (:request params))}]
    (assoc state request-id request)))

(defn- response-received [state {:keys [params]}]
  (dissoc state (:request-id params)))

(defn- frame-started-loading [state {:keys [params]}]
  ; frame reloaded - clear previous requests
  (let [frame-id (:frame-id params)]
    (->> state
         (remove #(= frame-id (:frame-id (second %))))
         (into {}))))

(deftype TaskTracker [tasks listeners]
  Closeable
  (close [_]
    (swap! listeners unsubscribe-all!)))

(defn init! [^ChromeDevToolsService tools]
  (let [tasks   (atom {})
        li      (atom [])
        page    (.getPage tools)
        network (.getNetwork tools)
        handle  #(reify EventHandler (onEvent [_ e] (% tasks e)))]
    (try
      (swap! li conj (.onFrameStartedLoading page (handle frame-started-loading)))
      (swap! li conj (.onRequestWillBeSent network (handle request-will-be-sent)))
      (swap! li conj (.onResponseReceived network (handle response-received)))
      (.enable page)
      (.enable network)
      (TaskTracker. tasks li)
      (catch Exception e
        (unsubscribe-all! li)
        (throw e)))))

