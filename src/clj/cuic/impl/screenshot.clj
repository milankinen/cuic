(ns cuic.impl.screenshot
  (:require [clojure.java.io :as io]
            [cuic.impl.util :refer [decode-base64]]
            [cuic.impl.browser :refer [tools]])
  (:import (java.text SimpleDateFormat)
           (java.util Date)))

(defn try-take-screenshot! [browser screenshot-dir]
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
