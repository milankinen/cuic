(ns ^:no-doc cuic.internal.cdt
  (:require [clojure.data.json :as json]
            [clojure.tools.logging :refer [debug error]]
            [gniazdo.core :as ws])
  (:import (java.lang AutoCloseable)
           (java.util.concurrent CountDownLatch TimeUnit LinkedBlockingDeque)
           (java.net SocketTimeoutException)
           (clojure.lang IDeref IFn Var)
           (org.eclipse.jetty.websocket.client WebSocketClient)
           (cuic DevtoolsProtocolException CuicException)))

(set! *warn-on-reflection* true)

(defn- handle-message [reqs ^LinkedBlockingDeque events-q msg-s]
  (try
    (when-let [msg (json/read-str msg-s :key-fn keyword)]
      (if-let [id (:id msg)]
        ;; Response to request
        (do (some-> (get @reqs id) (deliver msg))
            (swap! reqs dissoc id))
        ;; Event, will be processed in a separate thread
        (.putLast events-q msg)))
    (catch Throwable ex
      (error ex "Chrome Devtools WebSocket message receive error occurred"))))

(defn- handle-event [listeners {:keys [method params]}]
  (doseq [li (get @listeners method)]
    (try
      (li method params)
      (catch InterruptedException ex
        (throw ex))
      (catch Exception ex
        (error ex "Error occurred while handling event, method =" method)))))

(defn- handle-events [^LinkedBlockingDeque events-q listeners]
  (try
    (loop []
      (when-not (Thread/interrupted)
        (when-let [event (.takeFirst events-q)]
          (handle-event listeners event)
          (recur))))
    (catch InterruptedException _)
    (catch Throwable ex
      (error ex "Unexpected error occurred in event handler loop")))
  (.clear events-q))

(defn- handle-close [promises code reason]
  (debug "Chrome Devtools WebSocket connetion closed, code =" code "reason =" reason)
  (let [xs @promises]
    (reset! promises #{})
    (doseq [x xs] (deliver x ::closed))))

(defn- handle-error [^Throwable ex]
  (error ex "Chrome Devtools WebSocket connection error occurred"))

(defrecord ChromeDeveloperTools [socket event-handler requests listeners promises events-q next-id]
  AutoCloseable
  (close [_]
    (ws/close socket)
    (reset! listeners {})
    (reset! requests {})
    (when (.isAlive ^Thread event-handler)
      ;; signal closing with false, then interrupt and wait until
      ;; handler thread gets stopped
      (.putFirst ^LinkedBlockingDeque events-q false)
      (doto ^Thread event-handler
        (.interrupt)
        (.join 1000)))))

(defn cdt? [x]
  (instance? ChromeDeveloperTools x))

(defn connect [{:keys [url timeout]}]
  {:pre [(string? url)
         (pos-int? timeout)]}
  (try
    (let [reqs (atom {})
          listeners (atom {})
          promises (atom #{})
          next-id (atom 0)
          events-q (LinkedBlockingDeque.)
          event-handler (doto (Thread. (reify Runnable (run [_] (handle-events events-q listeners))))
                          (.setName "cuic-event-handler")
                          (.start))
          on-receive #(handle-message reqs events-q %)
          on-error handle-error
          on-close #(handle-close promises %1 %2)
          client (let [c ^WebSocketClient (ws/client)]
                   (doto (.getPolicy c)
                     (.setIdleTimeout 0)
                     (.setMaxTextMessageSize (* 50 1024 1024)))
                   (doto c
                     (.setConnectTimeout timeout)
                     (.start)))
          socket (ws/connect
                   url
                   :client client
                   :on-receive on-receive
                   :on-error on-error
                   :on-close on-close)]
      (->ChromeDeveloperTools socket event-handler reqs listeners promises events-q next-id))
    (catch SocketTimeoutException ex
      (throw (CuicException. "Chrome Devtools Websocket connection timeout" ex)))))

(defn disconnect [cdt]
  {:pre [(cdt? cdt)]}
  (.close ^AutoCloseable cdt))

(defn cdt-promise [cdt timeout]
  {:pre [(cdt? cdt)
         (nat-int? timeout)]}
  (let [d (CountDownLatch. 1)
        v (atom d)
        p (reify
            IDeref
            (deref [_]
              (if-not (or (and (zero? timeout)
                               (.await d)
                               true)
                          (.await d timeout TimeUnit/MILLISECONDS))
                (throw (DevtoolsProtocolException. "Operation timed out" -1))
                (let [r @v]
                  (when (= ::closed r)
                    (throw (DevtoolsProtocolException.
                             (str "Chrome Devtools were closed while"
                                  "waiting for operation to complete")
                             -1)))
                  r)))
            IFn
            (invoke [this x]
              (when (and (pos? (.getCount d))
                         (compare-and-set! v d x))
                (swap! (:promises cdt) disj this))
              (.countDown d)
              x))]
    (swap! (:promises cdt) conj p)
    p))

(defn invoke [{:keys [cdt cmd args timeout block?]
               :or   {timeout 10000
                      block?  true}}]
  {:pre [(cdt? cdt)
         (string? cmd)
         (map? args)
         (boolean? block?)]}
  (let [{:keys [socket requests next-id]} cdt
        req-id (dec (swap! next-id inc))
        payload {:id     req-id
                 :method cmd
                 :params args}
        body (json/write-str payload)
        res-p (cdt-promise cdt timeout)]
    (swap! requests assoc req-id res-p)
    (ws/send-msg socket body)
    (let [d (delay (let [{:keys [result error]} @res-p]
                     (when error
                       (throw (DevtoolsProtocolException. (:message error) (:code error))))
                     result))]
      (if block? @d d))))

(defrecord Subscription [cdt methods callback]
  AutoCloseable
  (close [_]
    (let [{:keys [listeners]} cdt]
      (doseq [method methods]
        (swap! listeners update method #(disj % callback))))))

(defn subscription? [x]
  (instance? Subscription x))

(defn on [{:keys [cdt methods callback]}]
  {:pre [(cdt? cdt)
         (set? methods)
         (every? string? methods)
         (ifn? callback)]}
  (let [{:keys [listeners]} cdt
        frame (Var/cloneThreadBindingFrame)
        cb (fn callback-wrapper [method args]
             (Var/resetThreadBindingFrame frame)
             (callback method args))]
    (doseq [method methods]
      (swap! listeners update method #(conj (or % #{}) cb)))
    (->Subscription cdt methods cb)))

(defn off [^Subscription subs]
  {:pre [(instance? Subscription subs)]}
  (.close subs))

(defn ex-code [ex]
  (when (instance? DevtoolsProtocolException ex)
    (.getCode ^DevtoolsProtocolException ex)))
