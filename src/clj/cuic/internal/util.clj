(ns ^:no-doc cuic.internal.util
  (:import (java.util Base64)
           (java.net MalformedURLException URI URISyntaxException)
           (cuic CuicException TimeoutException)
           (cuic DevtoolsProtocolException)
           (cuic.internal StaleObjectException)))

(set! *warn-on-reflection* true)

(defn safe-str [x]
  (try
    (pr-str x)
    (catch Exception _
      (str (type x)))))

(defn quoted [s]
  (when s (format "\"%s\"" s)))

(defn url-str? [s]
  (and (string? s)
       (try
         (let [uri (URI. s)]
           (or (boolean (seq (.getHost uri)))
               (and (= "file" (.getScheme uri))
                    (boolean (seq (.getPath uri))))))
         (catch URISyntaxException _
           false)
         (catch MalformedURLException _
           false))))

(defn decode-base64 [^String data]
  (let [d (Base64/getDecoder)]
    (.decode d data)))

(defn cuic-ex [& xs]
  (let [msg (apply str (interpose " " (filter some? xs)))]
    (CuicException. msg nil)))

(defn timeout-ex [& xs]
  (let [msg (apply str (interpose " " (filter some? xs)))]
    (TimeoutException. msg nil)))

(defn check-arg [[pred description] [arg arg-name]]
  (when-not (pred arg)
    (throw (cuic-ex (str "Expected " arg-name " to be " description
                         ", actual = " (safe-str arg))))))

(def ^:dynamic *hide-internal-stacktrace*
  (not= "true" (System/getProperty "cuic.exceptions.full_stacktrace")))

(defmacro rewrite-exceptions [& body]
  `(try
     (try
       ~@body
       (catch DevtoolsProtocolException dpe#
         (throw (CuicException. "Chrome DevTools error occurred" dpe#))))
     (catch CuicException ce#
       (when-not *hide-internal-stacktrace*
         (throw ce#))
       (.setStackTrace ce# (into-array (next (seq (.getStackTrace (Exception.))))))
       (throw ce#))))

(defmacro stale-as-nil [& body]
  `(try
     ~@body
     (catch StaleObjectException ex#)))

(defmacro stale-as-ex [ex & body]
  `(try
     ~@body
     (catch StaleObjectException ex#
       (throw ~ex))))
