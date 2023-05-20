(ns cuic.chrome
  "Functions for Chrome process launching and management"
  (:require [clojure.spec.alpha :as s]
            [clojure.tools.logging :refer [trace debug error fatal]]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.data.json :as json]
            [cuic.internal.cdt :as cdt]
            [cuic.internal.page :as page]
            [cuic.internal.runtime :as runtime])
  (:import (clojure.lang IPersistentVector IAtom)
           (java.lang.reflect Method)
           (java.util List Scanner)
           (java.util.concurrent TimeUnit)
           (java.lang ProcessBuilder$Redirect AutoCloseable)
           (java.nio.file Files LinkOption Path)
           (java.nio.file.attribute FileAttribute)
           (java.io IOException Writer File)
           (java.net ServerSocket URL HttpURLConnection)
           (cuic CuicException)))

(set! *warn-on-reflection* true)

(defn- get-chrome-binary-path ^Path []
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

(defn- get-pid [^Process proc]
  (try
    (if-let [pid ^Method (.getMethod Process "pid" (into-array Class []))]
      (.invoke pid proc (into-array Object []))
      -1)
    (catch Exception _
      -1)))

(defn- kill-proc [^Process proc]
  (when (.isAlive proc)
    (.destroy proc)
    (try
      (when-not (.waitFor proc 5 TimeUnit/SECONDS)
        (.destroyForcibly proc)
        (.waitFor proc 5 TimeUnit/SECONDS))
      (catch InterruptedException _
        (error "Interrupted while terminating Chrome process")
        (.destroyForcibly proc)))))

(defn- log-proc-output [^Process proc]
  (with-open [scanner (doto (Scanner. (.getInputStream proc))
                        (.useDelimiter (System/getProperty "line.separator")))]
    (try
      (while (and (.hasNext scanner)
                  (not (Thread/interrupted)))
        (let [output-line (.next scanner)]
          (trace output-line)))
      (catch InterruptedException _)
      (catch Exception ex
        (fatal ex "Unexpected exception occurred while reading Chrome output")))
    (trace "Chrome output logger stopped")))

(defn- start-loggers [^Process proc]
  (doto (Thread. (reify Runnable (run [_] (log-proc-output proc))))
    (.setDaemon true)
    (.start)))

(defn- stop-loggers [^Thread loggers]
  (.interrupt loggers)
  (.join loggers 1000))

(defn- delete-data-dir [^Path dir-path]
  (when dir-path
    (debug "Deleting temporary data directory" (str dir-path))
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

(defn- wait-for-devtools [{:keys [host port until] :as props}]
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

(defn- list-tabs [host port]
  (let [items (-> (format "http://%s:%d/json/list" host port)
                  (slurp)
                  (json/read-str :key-fn keyword))]
    (filter #(= "page" (:type %)) items)))

(defn- wait-for-tabs [host port until]
  (or (seq (list-tabs host port))
      (if (> (System/currentTimeMillis) until)
        (throw (CuicException. "Could not detect Chrome tabs"))
        (do (Thread/sleep 100)
            (recur host port until)))))

(defn- close-safely [body]
  {:pre [(fn? body)]}
  (try
    (body)
    (catch Exception ex
      (error ex "Unexpected Chrome shutdown error occurred"))))

(defrecord ^:no-doc Chrome
  [^Process process
   ^Thread loggers
   ^IPersistentVector args
   ^String data-dir
   ^Boolean destroy-data-dir?
   ^Long port
   ^IAtom page]
  AutoCloseable
  (close [_]
    (when-let [p @page]
      (reset! page nil)
      (close-safely #(page/detach p))
      (close-safely #(cdt/disconnect (page/get-page-cdt p)))
      (close-safely #(kill-proc process))
      (close-safely #(stop-loggers loggers))
      (when destroy-data-dir?
        (close-safely #(delete-data-dir data-dir))))))

(defmethod print-method Chrome [{:keys [^Process process args port data-dir]} writer]
  (let [props {:executable (first args)
               :pid        (when (.isAlive process)
                             (get-pid process))
               :args       (subvec args 1)
               :data-dir   (str data-dir)
               :cdp-port   port}]
    (.write ^Writer writer ^String (str "#chrome " (pr-str props)))))

(defn- long-prop [prop]
  (try
    (some-> (System/getProperty prop)
            (Long/parseLong))
    (catch Exception _)))

;;
;;

(defn chrome?
  "Checks whether the given value is a valid Chrome browser instance or not"
  [val]
  (instance? Chrome val))

(defn ^:no-doc get-current-page
  "Returns internal page object for the given Chrome instance. Do not
   use in your app code!"
  [chrome]
  {:pre [(chrome? chrome)]}
  (or @(:page chrome)
      (throw (CuicException. "Browser is closed"))))

(s/def ::width pos-int?)
(s/def ::height pos-int?)
(s/def ::headless (s/or :boolean boolean? :new-or-old (comp boolean #{"new" "old"})))
(s/def ::window-size (s/keys :req-un [::width ::height]))
(s/def ::disable-gpu boolean?)
(s/def ::remote-debugging-port pos-int?)
(s/def ::disable-hang-monitor boolean?)
(s/def ::disable-popup-blocking boolean?)
(s/def ::disable-prompt-on-repost boolean?)
(s/def ::safebrowsing-disable-auto-update boolean?)
(s/def ::no-first-run boolean?)
(s/def ::disable-sync boolean?)
(s/def ::metrics-recording-only boolean?)
(s/def ::user-data-dir (s/nilable #(instance? Path %)))
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
  "Default launch options for non-headless Chrome instance"
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
  "Default launch options for headless Chrome instance"
  (assoc defaults
    :disable-gpu true
    :mute-audio true
    :hide-scrollbars true))

(defn- resolve-defaults
  "Merges `options` with appropriate defaults based on whether running in
   headless mode or not."
  [{:keys [headless] :as options}]
  (cond
    (= headless false) (merge defaults options)
    ; unify {:headless true} and {:headless "old"}
    (= headless true) (merge headless-defaults (assoc options :headless "old"))
    :else (merge headless-defaults options)))

(defn launch
  "Launches a new local Chrome process with the given options and returns
   instance to the launched Chrome that can be used as a browser in
   [[cuic.core]] functions.

   The provided options have defaults using either [[cuic.chrome/defaults]]
   or  [[cuic.chrome/headless-defaults]] depending on whether the process
   was launched in headless mode or not. For full list of options, see
   https://peter.sh/experiments/chromium-command-line-switches. Option key
   will be the switch name without `--` prefix, If option is boolean type
   flag its value must be passed as a boolean, e.g. `--mute-audio` becomes
   `{:mute-audio true}`. Other arguments should be passed as strings. The
   only exceptions are:
      * `:window-size {:width <pos-int> :height <pos-int>}`
      * `:remote-debugging-port <pos-int>`
      * `:user-data-dir <java.nio.file.Path>`

   The function tries to auto-detect the path of the used Chrome binary,
   using known default locations. If you're using non-standard installation
   location, you can pass the custom path as a `java.nio.file.Path` parameter.
   The used installation must have **at least** Chromium version 64.0.3264.0
   (r515411).

   After the process is launched, the function waits until Chome devtools
   become available, using `-Dcuic.chrome.timeout=<ms>` timeout by default.
   This can be overrided with the last parameter. Devtools protocol port
   will be selected randomly unless explicitly specified in options.

   By default each launched process will get its own temporary data directory,
   meaning that the launched instances do not share any state at all. When
   the instance gets closed, its temporary data directory will be deleted
   as well. If you want to share state between separate instances/processes,
   you should pass the data directory as `:user-data-dir` option. In this case,
   the directory won't be removed even if the instance gets closed: the invoker
   of this function is responsible for managing the directory's creation, access
   and removal.

   Chrome instances implement `java.lang.AutoCloseable` so they can be used
   with Clojure's `with-open` macro.

   ```clojure
   ;; Launch headless instance using provided defaults and
   ;; auto-detected binary
   (def chrome (launch))

   ;; Launch non-headless instance using pre-defined data directory
   (def data-dir
     (doto (io/file \"chrome-data\")
       (.mkdirs)))
   (def chrome
     (launch {:headless      false
              :user-data-dir (.toPath data-dir)}))

   ;; Launch chrome from non-standard location
   (def chrome (launch {} (.toPath (io/file \"/tmp/chrome/google-chrome-stable\"))))

   ;; Launch headless instance and use it as a default browser
   ;; and close the instance after usage
   (with-open [chrome (launch)]
     (binding [c/*browser* chrome]
       ...do something...))
   ```
   "
  (^Chrome [] (launch {:headless "old"}))
  (^Chrome [options] (launch options (get-chrome-binary-path)))
  (^Chrome [options chrome-path] (launch options chrome-path (or (long-prop "cuic.chrome.timeout") 10000)))
  (^Chrome [options chrome-path timeout]
   {:pre [(or (instance? Path chrome-path)
              (string? chrome-path))
          (s/valid? ::options options)
          (pos-int? timeout)]}
   (let [custom-data-dir (:user-data-dir options)
         tmp-data-dir (when-not custom-data-dir
                        (-> (Files/createTempDirectory "cuic-user-data" (into-array FileAttribute []))
                            (.toAbsolutePath)))
         data-dir (or custom-data-dir tmp-data-dir)
         port (or (:remote-debugging-port options)
                  (get-free-port-for-cdp))
         options (merge (resolve-defaults options)
                        {:user-data-dir         (str data-dir)
                         :remote-debugging-port port})
         args (->> (filter second options)
                   (keep (fn [[k v]]
                           (cond
                             (= :window-size k) (str "--" (name k) "=" (:width v) "," (:height v))
                             (true? v) (str "--" (name k))
                             (false? v) nil
                             :else (str "--" (name k) "=" v))))
                   (into [(if-not (string? chrome-path)
                            (.toString (.toAbsolutePath ^Path chrome-path))
                            chrome-path)]))
         args (if (= (:headless options) "old")
                ; Chromium did a breaking change recently (version ~109), and when started headless,
                ; it seems it has "zero" tabs open. This can be fixed by providing and url and about:blank
                ; is short and failsafe way to provide one.
                (conj args "about:blank")
                args)
         _ (debug "Starting Chrome with command:" (string/join " " args))
         proc (-> (ProcessBuilder. ^List (apply list args))
                  (.redirectErrorStream true)
                  (.redirectOutput ProcessBuilder$Redirect/PIPE)
                  (.start))
         loggers (start-loggers proc)
         until (+ (System/currentTimeMillis) timeout)]
     (try
       (debug "Connecting to Chrome Devtools at port" port)
       (when-not (wait-for-devtools {:host "127.0.0.1" :port port :until until})
         (throw (CuicException. "Could not connect to Chrome Devtools")))
       (let [tabs (wait-for-tabs "127.0.0.1" port until)
             _ (trace "Got tabs" tabs)
             ws-url (-> tabs (first) :webSocketDebuggerUrl)
             _ (trace "Using Devtools websocket url" ws-url)
             cdt (cdt/connect {:url ws-url :timeout timeout})]
         (debug "Connected to Chrome Devtools at port" port)
         (when tmp-data-dir
           (doto (.toFile tmp-data-dir)
             (.deleteOnExit)))
         (runtime/initialize cdt)
         (cdt/invoke {:cdt cdt :cmd "Network.enable" :args {}})
         (->Chrome proc
                   loggers
                   args
                   data-dir
                   (nil? custom-data-dir)
                   port
                   (atom (page/attach cdt))))
       (catch Exception e
         (error e "Unexpected error occurred while connecting to Chrome Devtools")
         (kill-proc proc)
         (delete-data-dir tmp-data-dir)
         (if (instance? CuicException e)
           (throw e)
           (throw (CuicException. "Could not connect to Chrome Devtools" e))))))))

(defn terminate
  "Terminates the given Chrome instance and cleans up its temporary
   resources and directories."
  [chrome]
  {:pre [(chrome? chrome)]}
  (.close ^Chrome chrome))

(defn devtools
  "Returns handle to the Chrome devtools. To use the returned devtools,
   see functions from the [[cuic.chrome.protocol]] namespace."
  [chrome]
  {:pre [(chrome? chrome)]}
  (-> (get-current-page chrome)
      (page/get-page-cdt)))

(defn set-cache-disabled
  "Toggles cache for each request. If `true`, cache will not be used."
  [chrome disabled?]
  {:pre [(chrome? chrome)
         (boolean? disabled?)]}
  (cdt/invoke {:cdt  (devtools chrome)
               :cmd  "Network.setCacheDisabled"
               :args {:cacheDisabled disabled?}})
  nil)

(s/def ::http-headers
  (s/map-of string? string?))

(defn set-extra-headers
  "Specifies whether to always send extra HTTP headers with the requests from
   this page. Can be used e.g. for impersonation in your tests."
  [chrome http-headers]
  {:pre [(chrome? chrome)
         (s/valid? ::http-headers http-headers)]}
  (cdt/invoke {:cdt  (devtools chrome)
               :cmd  "Network.setExtraHTTPHeaders"
               :args {:headers (into {} http-headers)}})
  nil)
