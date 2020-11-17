(ns cuic.chrome
  (:require [clojure.spec.alpha :as s]
            [clojure.tools.logging :refer [trace debug warn error]]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.data.json :as json]
            [cuic.internal.cdt :as cdt]
            [cuic.internal.page :as page]
            [cuic.internal.runtime :as runtime])
  (:import (clojure.lang IPersistentVector IAtom)
           (java.util List Scanner)
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

(defn- log-proc-output [^Process proc]
  (with-open [scanner (doto (Scanner. (.getInputStream proc))
                        (.useDelimiter (System/getProperty "line.separator")))]
    (try
      (while (and (.hasNext scanner)
                  (not (Thread/interrupted)))
        (trace (.next scanner)))
      (catch InterruptedException _)
      (catch Exception ex
        (error ex "Unexpected exception occurred while reading Chrome output")))
    (trace "Chrome output logger stopped")))

(defn- start-loggers [^Process proc]
  (doto (Thread. #(log-proc-output proc))
    (.setDaemon true)
    (.start)))

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

(defrecord ^:no-doc Chrome
  [^Process process
   ^Thread loggers
   ^IPersistentVector args
   ^String data-dir
   ^Boolean destroy-data-dir?
   ^Long port
   ^IAtom tools]
  AutoCloseable
  (close [_]
    (when-let [{:keys [page cdt]} @tools]
      (reset! tools nil)
      (page/detach page)
      (cdt/disconnect cdt)
      (terminate process)
      (when destroy-data-dir?
        (delete-data-dir data-dir)))))

(defmethod print-method Chrome [{:keys [^Process process args port data-dir]} writer]
  (let [props {:executable (first args)
               :pid        (when (.isAlive process)
                             (.pid process))
               :args       (subvec args 1)
               :data-dir   (str data-dir)
               :cdp-port   port}]
    (.write ^Writer writer ^String (str "#chrome " (pr-str props)))))

(defn- long-prop [prop]
  (try
    (some-> (System/getProperty prop)
            (Long/parseLong))
    (catch Exception _)))

(defn- get-tools [chrome]
  (or @(:tools chrome)
      (throw (CuicException. "Browser is closed"))))

;;
;;

(defn chrome?
  "Checks whether the given value is valid Chrome browser instance or not"
  [val]
  (instance? Chrome val))

(defn ^:no-doc page
  "Returns internal page object for the given Chrome instance. Do not
   use in your app code!"
  [chrome]
  {:pre [(chrome? chrome)]}
  (:page (get-tools chrome)))

(s/def ::width pos-int?)
(s/def ::height pos-int?)
(s/def ::headless boolean?)
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

(defn ^Chrome launch
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
  ([] (launch {:headless true}))
  ([options] (launch options (get-chrome-binary-path)))
  ([options chrome-path] (launch options chrome-path (or (long-prop "cuic.chrome.timeout") 10000)))
  ([options chrome-path timeout]
   {:pre [(instance? Path chrome-path)
          (s/valid? ::options options)
          (pos-int? timeout)]}
   (let [custom-data-dir (:user-data-dir options)
         tmp-data-dir (when-not custom-data-dir
                        (-> (Files/createTempDirectory "cuic-user-data" (into-array FileAttribute []))
                            (.toAbsolutePath)))
         data-dir (or custom-data-dir tmp-data-dir)
         port (or (:remote-debugging-port options)
                  (get-free-port-for-cdp))
         args (->> (merge (if (:headless options)
                            headless-defaults
                            defaults)
                          options
                          {:user-data-dir         (str data-dir)
                           :remote-debugging-port port})
                   (filter second)
                   (keep (fn [[k v]]
                           (cond
                             (= :window-size k) (str "--" (name k) "=" (:width v) "," (:height v))
                             (true? v) (str "--" (name k))
                             (false? v) nil
                             :else (str "--" (name k) "=" v))))
                   (into [(.toString (.toAbsolutePath ^Path chrome-path))]))
         _ (debug "Starting Chrome with command:" (string/join " " args))
         proc (-> (ProcessBuilder. ^List (apply list args))
                  (.redirectErrorStream true)
                  (.redirectOutput ProcessBuilder$Redirect/PIPE)
                  (.redirectErrorStream true)
                  (.start))
         loggers (start-loggers proc)]
     (try
       (debug "Connecting to Chrome Devtools at port" port)
       (when-not (wait-for-devtools {:host  "localhost"
                                     :port  port
                                     :until (+ (System/currentTimeMillis) timeout)})
         (throw (CuicException. "Could not connect to Chrome Devtools")))
       (let [tabs (list-tabs "localhost" port)
             ws-url (-> tabs (first) :webSocketDebuggerUrl)
             cdt (cdt/connect ws-url)]
         (debug "Connected to Chrome Devtools at port" port)
         (when tmp-data-dir
           (doto (.toFile tmp-data-dir)
             (.deleteOnExit)))
         (runtime/initialize cdt)
         (->Chrome proc
                   loggers
                   args
                   data-dir
                   (nil? custom-data-dir)
                   port
                   (atom {:cdt cdt :page (page/attach cdt)})))
       (catch Exception e
         (terminate proc)
         (delete-data-dir tmp-data-dir)
         (if (instance? CuicException e)
           (throw e)
           (throw (CuicException. "Could not connect to Chrome Devtools" e))))))))

(defn ^:no-doc devtools
  "Returns handle to the Chrome devtools. Not exposed as public
   function because the lack of public low-level API."
  [chrome]
  {:pre [(chrome? chrome)]}
  (:cdt (get-tools chrome)))
