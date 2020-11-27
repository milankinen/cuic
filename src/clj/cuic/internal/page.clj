(ns ^:no-doc cuic.internal.page
  (:require [cuic.internal.cdt :refer [invoke on off cdt-promise]])
  (:import (java.lang AutoCloseable)))

(set! *warn-on-reflection* true)

(defrecord Page [cdt state subscription]
  AutoCloseable
  (close [_]
    (off subscription)))

(defn- main-frame-id [page]
  (get-in @(:state page) [:main-frame :id]))

(defn- handle-lifecycle-event [state {:keys [name frameId]}]
  (letfn [(handle [{:keys [main-frame] :as s}]
            (if (= (:id main-frame) frameId)
              (if (= "init" name)
                (assoc s :events #{})
                (update s :events conj name))
              s))]
    (swap! state handle)))

(defn- handle-event [state method params]
  (case method
    "Page.lifecycleEvent" (handle-lifecycle-event state params)
    nil))

(defn- navigate [{:keys [cdt] :as page} navigation-op timeout]
  (let [p (cdt-promise cdt timeout)
        frame-id (main-frame-id page)
        cb (fn [method {:keys [frameId name]}]
             (when (or (and (= "Page.lifecycleEvent" method)
                            (= frameId frame-id)
                            (= "load" name))
                       (and (= "Page.navigatedWithinDocument" method)
                            (= frameId frame-id)))
               (deliver p ::ok)))
        subs (on {:cdt      cdt
                  :methods  #{"Page.lifecycleEvent"
                              "Page.navigatedWithinDocument"}
                  :callback cb})]
    (try
      (navigation-op)
      (when (pos-int? timeout)
        @p)
      (finally
        (off subs)))))

;;;;

(defn page? [x]
  (some? x))

(defn attach [cdt]
  (let [;; Get initial main frame
        main-frame (-> (invoke {:cdt  cdt
                                :cmd  "Page.getFrameTree"
                                :args {}})
                       (get-in [:frameTree :frame]))
        ;; Initialize page state
        state (atom {:main-frame main-frame :events #{}})
        ;; Attach listeners for lifecycle events
        subs (on {:cdt      cdt
                  :methods  #{"Page.lifecycleEvent"}
                  :callback #(handle-event state %1 %2)})]
    ;; Enable events
    (invoke {:cdt  cdt
             :cmd  "Page.enable"
             :args {}})
    (invoke {:cdt  cdt
             :cmd  "Page.setLifecycleEventsEnabled"
             :args {:enabled true}})
    ;; Return handle to the page
    (->Page cdt state subs)))

(defn detach [page]
  {:pre [(page? page)]}
  #_(.close ^Page page))

(defn navigate-to [{:keys [cdt] :as page} url timeout]
  {:pre [(page? page)
         (string? url)
         (nat-int? timeout)]}
  (let [op #(invoke {:cdt  cdt
                     :cmd  "Page.navigate"
                     :args {:url url}})]
    (navigate page op timeout)))

(defn navigate-back [{:keys [cdt] :as page} timeout]
  {:pre [(page? page)
         (nat-int? timeout)]}
  (let [history (invoke {:cdt  cdt
                         :cmd  "Page.getNavigationHistory"
                         :args {}})
        idx (:currentIndex history)
        entry (->> (take idx (:entries history))
                   (last))
        op #(invoke {:cdt  cdt
                     :cmd  "Page.navigateToHistoryEntry"
                     :args {:entryId (:id entry)}})]
    (when entry
      (navigate page op timeout))))

(defn navigate-forward [{:keys [cdt] :as page} timeout]
  {:pre [(page? page)
         (nat-int? timeout)]}
  (let [history (invoke {:cdt  cdt
                         :cmd  "Page.getNavigationHistory"
                         :args {}})
        idx (:currentIndex history)
        entry (->> (drop (inc idx) (:entries history))
                   (first))
        op #(invoke {:cdt  cdt
                     :cmd  "Page.navigateToHistoryEntry"
                     :args {:entryId (:id entry)}})]
    (when entry
      (navigate page op timeout))))
