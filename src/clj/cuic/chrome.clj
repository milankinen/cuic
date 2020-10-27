(ns cuic.chrome
  (:require [clojure.spec.alpha :as s]
            [clojure.tools.logging :refer [trace warn]]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.data.json :as json]
            [cuic.internal.cdt :as cdt]
            [cuic.internal.page :as page]
            [cuic.internal.runtime :as runtime])
  (:import (java.util List)
           (java.util.concurrent TimeUnit)
           (java.lang ProcessBuilder$Redirect AutoCloseable)
           (java.nio.file Files LinkOption Path)
           (java.nio.file.attribute FileAttribute)
           (java.io IOException Writer File)
           (java.net ServerSocket URL HttpURLConnection)
           (cuic CuicException)))

(set! *warn-on-reflection* true)

(defn- ^Path get-chrome-binary-path []
  (or (->> ["/usr/bin/chromium",
            "/usr/bin/chromium-browser",
            "/usr/bin/google-chrome-stable",
            "/usr/bin/google-chrome",
            "/Applications/Chromium.app/Contents/MacOS/Chromium",
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
            "/Applications/Google Chrome Canary.app/Contents/MacOS/Google Chrome Canary",
            "C:/Program Files (x86)/Google/Chrome/Application/chrome.exe"]
           (map #(.toPath (io/file %)))
           (filter #(and (Files/isRegularFile % (into-array LinkOption []))
                         (Files/isReadable %)
                         (Files/isExecutable %)))
           (first))
      (throw (CuicException. "Chrome binary not found" nil))))

(defn- terminate [^Process proc]
  (when (.isAlive proc)
    (.destroy proc)
    (try
      (when-not (.waitFor proc 5 TimeUnit/SECONDS)
        (.destroyForcibly proc)
        (.waitFor proc 5 TimeUnit/SECONDS))
      (catch InterruptedException ex
        (warn ex "Interrupted while terminating Chrome process")
        (.destroyForcibly proc)))))

(defn- delete-data-dir [^Path dir-path]
  (when dir-path
    (doseq [file (reverse (file-seq (.toFile dir-path)))]
      (try
        (.delete ^File file)
        (catch Exception _)))))

(defn- get-free-port-for-cdp []
  (try
    (with-open [socket (ServerSocket. 0)]
      (.getLocalPort socket))
    (catch Exception e
      (throw (CuicException. "Could not find free port for Chrome debug protocol" e)))))

(defn wait-for-devtools [{:keys [host port until] :as props}]
  (let [url (URL. (format "http://%s:%d/json/version" host port))
        conn ^HttpURLConnection (.openConnection url)
        status (try
                 (doto conn
                   (.setRequestMethod "HEAD")
                   (.setDoOutput false)
                   (.setConnectTimeout 1000)
                   (.setReadTimeout 1000))
                 (.connect conn)
                 (when (not= 200 (.getResponseCode conn))
                   (throw (IOException. "Non-200 response code")))
                 (.disconnect conn)
                 :ok
                 (catch IOException _
                   (if (< (System/currentTimeMillis) until)
                     :retry
                     :nok)))]
    (case status
      :ok true
      :nok false
      (do (Thread/sleep 100)
          (recur props)))))

(defn list-tabs [host port]
  (let [items (-> (format "http://%s:%d/json/list" host port)
                  (slurp)
                  (json/read-str :key-fn keyword))]
    (filter #(= "page" (:type %)) items)))


(defrecord Chrome [process args tmp-data-dir cdt port page]
  AutoCloseable
  (close [_]
    (page/detach page)
    (cdt/disconnect cdt)
    (terminate process)
    (delete-data-dir tmp-data-dir)))

(defmethod print-method Chrome [{:keys [args port]} writer]
  (let [props {:executable (first args)
               :args       (subvec args 1)
               :cdp-port   port}]
    (.write ^Writer writer ^String (str "#chrome " (pr-str props)))))

(defn- long-prop [prop]
  (try
    (some-> (System/getProperty prop)
            (Long/parseLong))
    (catch Exception _)))

;;
;;

(s/def ::width pos-int?)
(s/def ::height pos-int?)
(s/def ::headless boolean?)
(s/def ::window-size (s/keys :req-un [::width ::height]))
(s/def ::disable-gpu boolean?)
(s/def ::remote-debugging-port integer?)
(s/def ::disable-hang-monitor boolean?)
(s/def ::disable-popup-blocking boolean?)
(s/def ::disable-prompt-on-repost boolean?)
(s/def ::safebrowsing-disable-auto-update boolean?)
(s/def ::no-first-run boolean?)
(s/def ::disable-sync boolean?)
(s/def ::metrics-recording-only boolean?)
(s/def ::user-data-dir (s/nilable string?))
(s/def ::disable-extensions boolean?)
(s/def ::disable-client-side-phishing-detection boolean?)
(s/def ::incognito boolean?)
(s/def ::disable-default-apps boolean?)
(s/def ::disable-background-timer-throttling boolean?)
(s/def ::disable-background-networking boolean?)
(s/def ::no-default-browser-check boolean?)
(s/def ::additional-arguments (s/map-of string? string?))
(s/def ::mute-audio boolean?)
(s/def ::disable-translate boolean?)
(s/def ::hide-scrollbars boolean?)

(s/def ::options
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
           ::mute-audio
           ::disable-translate
           ::hide-scrollbars]))

(def defaults
  {:window-size                            nil
   :disable-gpu                            false
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
   :mute-audio                             false
   :disable-translate                      true
   :hide-scrollbars                        false})

(def headless-defaults
  (assoc defaults
    :disable-gpu true
    :mute-audio true
    :hide-scrollbars true))

(defn ^Chrome launch
  ([] (launch {:headless true}))
  ([options] (launch options (or (long-prop "cuic.chrome.timeout") 10000)))
  ([options timeout]
   {:pre [(s/valid? ::options options)
          (pos-int? timeout)]}
   (let [chrome-path (get-chrome-binary-path)
         user-data-dir (:user-data-dir options)
         tmp-data-dir (when-not user-data-dir
                        (-> (Files/createTempDirectory "cuic-user-data" (into-array FileAttribute []))
                            (.toAbsolutePath)))
         port (or (:remote-debugging-port options)
                  (get-free-port-for-cdp))
         args (->> (merge (if (:headless options)
                            headless-defaults
                            defaults)
                          options
                          {:user-data-dir         (or user-data-dir tmp-data-dir)
                           :remote-debugging-port port})
                   (filter second)
                   (map (fn [[k v]]
                          (cond
                            (= :window-size k) (str "--" (name k) "=" (:width v) "," (:height v))
                            (true? v) (str "--" (name k))
                            :else (str "--" (name k) "=" v))))
                   (into [(.toString (.toAbsolutePath chrome-path))]))
         _ (trace "Starting Chrome with command:" (string/join " " args))
         proc (-> (ProcessBuilder. ^List (apply list args))
                  (.redirectErrorStream true)
                  (.redirectOutput ProcessBuilder$Redirect/PIPE)
                  (.start))]
     (try
       (trace "Connecting to Chrome Devtools at port" port)
       (when-not (wait-for-devtools {:host  "localhost"
                                     :port  port
                                     :until (+ (System/currentTimeMillis) timeout)})
         (throw (CuicException. "Could not connect to Chrome Devtools")))
       (let [tabs (list-tabs "localhost" port)
             ws-url (-> tabs (first) :webSocketDebuggerUrl)
             cdt (cdt/connect ws-url)]
         (trace "Connected to Chrome Devtools at port" port)
         (when tmp-data-dir
           (doto (.toFile tmp-data-dir)
             (.deleteOnExit)))
         (runtime/initialize cdt)
         (->Chrome proc args tmp-data-dir cdt port (page/attach cdt)))
       (catch Exception e
         (terminate proc)
         (delete-data-dir tmp-data-dir)
         (if (instance? CuicException e)
           (throw e)
           (throw (CuicException. "Could not connect to Chrome Devtools" e))))))))

(defn chrome? [val]
  (instance? Chrome val))

(defn devtools [chrome]
  {:pre [(chrome? chrome)]}
  (:cdt chrome))

(defn page [chrome]
  {:pre [(chrome? chrome)]}
  (:page chrome))
