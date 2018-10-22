(ns cuic.test
  (:require [clojure.test :as t]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as string]
            [clojure.walk :refer [postwalk]]
            [clojure.tools.logging :refer [debug]]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [cuic.core :refer [wait *browser* *config*]]
            [cuic.impl.retry :as retry])
  (:import (java.io File)
           (cuic WaitTimeoutException AbortTestError)))

(defn- sorted-map-keys [x]
  (postwalk #(if (map? %) (into (sorted-map) %) %) x))

(defn- snapshots-root-dir ^File []
  (io/file (:snapshot-dir *config*)))

(defn- sanitize-filename [filename]
  (-> filename
      (string/replace #"-" "_")
      (string/replace #"[^\w.]+" "")))

(defn- snapshot-filename [id]
  (sanitize-filename (name id)))

(defn- snapshot-dir [id]
  (loop [[d & ds] (filter seq (string/split (or (namespace id) "") #"\."))
         dir (snapshots-root-dir)]
    (if d (recur ds (io/file dir (sanitize-filename d))) dir)))

(defn expected-file [id ext]
  (io/file (snapshot-dir id) (str (snapshot-filename id) ".expected." ext)))

(defn actual-file [id ext]
  (io/file (snapshot-dir id) (str (snapshot-filename id) ".actual." ext)))

(defn read-edn [^File f]
  (if (.exists f)
    (edn/read-string (slurp f))))

(defn- ensure-snapshot-dir! [id]
  (let [dir    (snapshot-dir id)
        ignore (io/file (snapshots-root-dir) ".gitignore")]
    (when-not (.exists dir)
      (.mkdirs dir))
    (when-not (.exists ignore)
      (spit ignore "*.actual*\n"))))

(defn- test-snapshot [id actual predicate read write! ext]
  (ensure-snapshot-dir! id)
  (let [e-file   (expected-file id ext)
        a-file   (actual-file id ext)
        expected (read e-file)]
    (if (.exists e-file)
      (if-not (predicate expected actual)
        (do (write! (io/file a-file) actual)
            false)
        (do (if (.exists a-file) (.delete a-file))
            true))
      (do (write! e-file actual)
          (println " > Snapshot written : " (.getAbsolutePath e-file))
          true))))

(defmacro -with-retry [f expr-form]
  `(let [report# (atom nil)
         result# (with-redefs [t/do-report #(reset! report# %)]
                   (try
                     (retry/loop* ~f *browser* *config* ~expr-form)
                     (catch WaitTimeoutException e#
                       (if-some [cause# (.getCause e#)]
                         (throw cause#)
                         (:actual (ex-data e#))))))]
     (some-> @report# (t/do-report))
     (if (and (contains? #{:fail :error} (:type @report#))
              (true? (:abort-on-failed-assertion *config*)))
       (throw (AbortTestError.)))
     result#))

(defmacro is*
  "Assertion macro that works like clojure.test/is but if the result value is
   non-truthy or asserted expression throws a cuic exception, then expression
   is re-tried until truthy value is received or timeout exceeds."
  [form]
  `(let [do-report# t/do-report]
     (with-redefs [t/do-report #(if (instance? AbortTestError (:actual %))
                                  (throw (:actual %))
                                  (do-report# %))]
       (t/is (-with-retry #(do ~(t/assert-expr nil form)) '~form)))))


(defn matches-snapshot?
  [snapshot-id actual]
  {:pre [(keyword? snapshot-id)]}
  (let [predicate #(= %1 %2)
        read      read-edn
        write!    #(spit (io/file %1) (with-out-str (pprint (sorted-map-keys %2))))]
    (test-snapshot snapshot-id actual predicate read write! "edn")))

(defn -assert-snapshot [msg [match? id actual]]
  `(let [id#       (do ~id)
         actual#   (do ~actual)
         expected# (read-edn (expected-file id# "edn"))
         result#   (~match? id# actual#)]
     (if result#
       (t/do-report
         {:type     :pass
          :message  ~msg
          :expected '(~'matches-snapshot? ~id ~actual)
          :actual   (cons '~'= [expected# actual#])})
       (t/do-report
         {:type     :fail
          :message  ~msg
          :expected '(~'matches-snapshot? ~id ~actual)
          :actual   (list '~'not (cons '~'= [expected# actual#]))}))
     result#))

(defn -assert-with-retry [msg form]
  `(try
     ~form
     (catch Throwable t#
       (t/do-report
         {:type     :error
          :message  ~msg
          :expected ~(last form)
          :actual   t#}))))

(defmethod t/assert-expr 'matches-snapshot? [msg form]
  (-assert-snapshot msg form))

(defmethod t/assert-expr `matches-snapshot? [msg form]
  (-assert-snapshot msg form))

(defmethod t/assert-expr '-with-retry [msg form]
  (-assert-with-retry msg form))

(defmethod t/assert-expr `-with-retry [msg form]
  (-assert-with-retry msg form))
