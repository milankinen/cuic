(ns cuic.impl.task-tracking
  (:require [clojure.string :as string]
            [clojure.core.async :refer [go-loop <!]]
            [clojure.tools.logging :refer [debug info error]]
            [clj-chrome-devtools.events :as events]
            [clj-chrome-devtools.commands.page :as page]
            [clj-chrome-devtools.commands.network :as network]
            [cuic.impl.ws-invocation :refer [call]])
  (:import (java.io Closeable)))

(defn- handle-request-sent [state {:keys [params]}]
  (let [request-id (:request-id params)
        request    {:task-type    :http-request
                    :request-type (keyword (string/lower-case (:type params)))
                    :url          (:url (:request params))
                    :method       (:method (:request params))
                    :frame-id     (:frame-id (:request params))}]
    (assoc state request-id request)))

(defn- handle-request-finished [state {:keys [params]}]
  (dissoc state (:request-id params)))

(defn- handle-frame-load [state {:keys [params]}]
  ; frame reloaded - clear previous requests
  (let [frame-id (:frame-id params)]
    (->> state
         (remove #(= frame-id (:frame-id (second %))))
         (into {}))))

(defn- stop! [track-state conn]
  (when track-state
    (debug "Stopping task tracking")
    (doseq [[[domain event] ch] (:channels track-state)]
      (try
        (events/unlisten conn domain event ch)
        (catch Exception e
          (error "Got error when closing tracking" domain event ", message:" (.getMessage e)))))
    nil))

(defrecord Tracking [conn state-atom]
  Closeable
  (close [_]
    (swap! state-atom stop! conn)))

(defn start! [conn]
  (let [state-atom (atom {:channels {} :state {}})]
    (letfn [(listen! [domain event handler]
              (let [ch (events/listen conn domain event)]
                (swap! state-atom assoc-in [:channels [domain event]] ch)
                (go-loop []
                  (when-let [msg (<! ch)]
                    (swap! state-atom update :state #(handler % msg))
                    (recur)))))]
      (try
        (debug "Start task tracking")
        (call network/enable conn {})
        (call page/enable conn {})
        ; anonymous functions for easier REPLing
        (listen! :page :frame-started-loading #(handle-frame-load %1 %2))
        (listen! :network :request-will-be-sent #(handle-request-sent %1 %2))
        (listen! :network :response-received #(handle-request-finished %1 %2))
        (->Tracking conn state-atom)
        (catch Exception e
          (swap! state-atom stop! conn)
          (throw e))))))

(defn get-active-tasks [tracking]
  (vals (:state @(:state-atom tracking))))