(ns cuic.impl.retry
  (:require [clojure.java.io :as io]
            [cuic.impl.exception :as ex]
            [cuic.impl.util :refer [decode-base64]]
            [cuic.impl.browser :refer [tools]])


  (:import (java.text SimpleDateFormat)
           (java.util Date)))

(defn- try-run [f]
  (try
    {:value (f)}
    (catch Throwable e
      (if (ex/retryable? e)
        {:error e}
        (throw e)))))

(defn- try-take-screenshot! [browser screenshot-dir]
  (try
    (let [dir (io/file screenshot-dir)
          ts  (.format (SimpleDateFormat. "yyyy-MM-dd'T'HH-mm-ss-SSS") (Date.))]
      (if-not (.exists dir)
        (.mkdirs dir))
      (loop [i 1]
        (let [f (io/file dir (str "screenshot--" ts "." i ".png"))]
          (if (.exists f)
            (recur (inc i))
            (do (-> (.getPage (tools browser))
                    (.captureScreenshot)
                    (decode-base64)
                    (io/copy f))
                (println " > Screenshot saved to:" (.getAbsolutePath f)))))))
    (catch Exception e
      (println " > Could not take screenshot:" (.getMessage e)))))

(defn- do-timeout [expr-form descr value error browser {:keys [take-screenshot-on-timeout screenshot-dir]}]
  (if (and (some? browser)
           (true? take-screenshot-on-timeout))
    (try-take-screenshot! browser screenshot-dir))
  (throw (ex/timeout expr-form descr value error)))


(defn loop* [f browser {:keys [timeout] :as config} expr-form]
  (loop [start (System/currentTimeMillis)]
    (let [{:keys [value error] :as res} (try-run f)]
      (if value
        value
        (do (when (> (- (System/currentTimeMillis) start) timeout)
              (let [descr (if (contains? res :value)
                            (str ", latest value was: " (pr-str value)))]
                (do-timeout expr-form descr value error browser config)))
            (Thread/sleep 50)
            (recur start))))))

