(ns cuic.test
  (:require [clojure.test :as t]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as string]
            [clojure.walk :refer [postwalk]]
            [clojure.tools.logging :refer [debug]]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [cuic.core :refer [wait *browser*]]
            [cuic.impl.screenshot :refer [try-take-screenshot!]]
            [cuic.impl.exception :as ex])
  (:import (java.io File)
           (cuic WaitTimeoutException AbortTestError CuicException)))

(declare matches-snapshot?)
(declare ^:dynamic *screenshot-dir*)
(declare ^:dynamic *snapshot-dir*)

(defn- sorted-map-keys [x]
  (postwalk #(if (map? %) (into (sorted-map) %) %) x))

(defn- snapshots-root-dir ^File []
  (io/file *snapshot-dir*))

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

(defn __cuic-internal-try-take-screenshot__ []
  (when (and *browser*
             *screenshot-dir*)
    (try-take-screenshot! *browser* *screenshot-dir*)))


(defn __cuic-internal-assert-snapshot__ [msg [_ id actual]]
  `(let [id#       (do ~id)
         actual#   (do ~actual)
         expected# (read-edn (expected-file id# "edn"))
         result#   (matches-snapshot? id# actual#)]
     (if result#
       (t/do-report
         {:type     :pass
          :message  ~msg
          :expected '(~'matches-snapshot? ~id ~actual)
          :actual   (cons '~'= [expected# actual#])})
       (t/do-report
         {:type     :fail
          :message  ~msg
          :expected (cons '~'= [expected# actual#])
          :actual   (list '~'not (cons '~'= [expected# actual#]))}))
     result#))


(defmethod t/assert-expr '__cuic-internal-assert-retryable__ [_ [_ tested-form]]
  `(let [report# (atom nil)
         result# (with-redefs [t/do-report #(reset! report# %)]
                   (try
                     {:value (wait ~(t/assert-expr nil tested-form))}
                     (catch WaitTimeoutException e#
                       (swap! report# #(or % {:type     :fail
                                              :message  nil
                                              :expected '~tested-form
                                              :actual   (list '~'not '~tested-form)}))
                       {:error e#})
                     (catch Throwable t#
                       (reset! report# {:type     :error
                                        :message  nil
                                        :expected '~tested-form
                                        :actual   t#})
                       {:error t#})))]
     (some-> @report# (t/do-report))
     result#))

;; Public API

(defonce ^{:dynamic true
           :doc     "Root directory where screenshots will be saved on failed tests.
                     Set this to `nil` in order to disable screenshots."} *screenshot-dir*
  "target/__screenshots__")

(defonce ^{:dynamic true
           :doc     "Root directory for saved snapshots from `matches-snapshot?`"} *snapshot-dir*
  "test/__snapshots__")

(defmacro deftest*
  "Macro that works like `deftest` from `clojure.test`. It also surrounds
   the test body with try-catch block which captures all thrown CUIC exceptions
   and failed `is*` assertions and tries to take a screenshot from the current
   browser window for further inspection."
  [name & body]
  `(t/deftest ~name
     (try
       ~@body
       (catch AbortTestError ex#
         (debug ex# "Test aborted due to failed assertion")
         (__cuic-internal-try-take-screenshot__))
       (catch CuicException ex#
         (t/do-report {:type     :error
                       :message  nil
                       :expected nil
                       :actual   ex#})
         (__cuic-internal-try-take-screenshot__)))))

(defmacro is*
  "Assertion macro that works like `is` from `clojure.test` with two differences:

    * If asserted form returns non-truthy value it will automatically be retried
      until truthy value is received or timeout exceeds (implicit `c/wait`)
    * If assertion fails or errors is throw from the assertion,
      `cuic.AbortTestError` is throw, indicating that test should be aborted
      immediately without evaluating any further steps.

   Normally you should pair this macro with CUIC's `deftest*` to get screenshot from
   the failed page and to suppress extra errors caused by default `clojure.test` test
   reporter."
  [form]
  `(let [res# (t/is (~'__cuic-internal-assert-retryable__ ~form))]
     (if (some? (:error res#))
       (throw (ex/abort-test-ex))
       (:value res#))))

(defn matches-snapshot?
  "Tries to match the given actual data to the snapshot associated to the
   given id (keyword). If snapshot does not exist, this function creates it.
   All data that can be serialized with `pr-str` can be tested with snapshot
   testing.

   **Hint:** Use fully qualified keyword to distinguish snapshots from different
   test namespaces. You can also share same snapshots by using same id."
  [snapshot-id actual]
  {:pre [(keyword? snapshot-id)]}
  (let [predicate #(= %1 %2)
        read      read-edn
        write!    #(spit (io/file %1) (with-out-str (pprint (sorted-map-keys %2))))]
    (test-snapshot snapshot-id actual predicate read write! "edn")))

;
; Custom assertion expressions for cleaner test reporting for snapshot failures.
;

(defmethod t/assert-expr 'matches-snapshot? [msg form]
  (__cuic-internal-assert-snapshot__ msg form))

(defmethod t/assert-expr `matches-snapshot? [msg form]
  (__cuic-internal-assert-snapshot__ msg form))
