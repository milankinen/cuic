(ns cuic.impl.session
  (:require [clojure.tools.logging :refer [debug]]
            [clj-chrome-devtools.automation.fixture :refer [find-chrome-binary]])
  (:import (java.io Closeable File)
           (org.apache.commons.io FileUtils)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defrecord Session [executable ^File directory window-size]
  Closeable
  (close [_]
    (if (some-> directory (.exists))
      (FileUtils/forceDelete directory))))

(defn create! [executable window-size]
  (let [dir (doto (.toFile (Files/createTempDirectory "cuic-session" (into-array FileAttribute [])))
              (.deleteOnExit))]
    (debug "Create new session, using directory: " (.getAbsolutePath dir))
    (->Session (or executable (find-chrome-binary)) dir window-size)))

(defn default [executable]
  (->Session (or executable (find-chrome-binary)) nil nil))
