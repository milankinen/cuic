(ns cuic-tests.assertions-test
  (:require [clojure.test :refer [deftest testing use-fixtures is do-report]]
            [clojure.java.io :as io]
            [cuic.core :as c]
            [cuic.test :as ct :refer [matches-snapshot?]]
            [cuic.impl.exception :as ex])
  (:import (org.apache.commons.io FileUtils)
           (cuic AbortTestError)))

(defn throw-once [ex]
  (let [thrown (atom false)]
    (fn []
      (when-not @thrown
        (reset! thrown true)
        (throw ex))
      true)))

(def snapshot-dir "target/snapshot")

(defn assert-fixture [t]
  (binding [c/*timeout*       500
            ct/*snapshot-dir* snapshot-dir]
    (t)))


(use-fixtures :each assert-fixture)

(defmacro run-is-test [expr]
  `(let [reports# (atom [])
         thrown#  (atom false)]
     (try
       (with-redefs [do-report #(swap! reports# conj %)]
         (ct/is* ~expr))
       (catch AbortTestError e#
         (reset! thrown# true)))
     [@reports# @thrown#]))

(defn snapshot-exists? [pattern]
  (->> (file-seq (io/file snapshot-dir))
       (filter #(re-find pattern (.getName %)))
       (empty?)
       (not)))

(deftest is*-macro
  (testing "waits until asserted expression is truthy or fails after certain wait period"
    (is (= [[{:actual   true
              :expected true
              :message  nil
              :type     :pass}]
            false]
           (run-is-test true)))
    (is (= [[{:actual   "tsers"
              :expected "tsers"
              :message  nil
              :type     :pass}]
            false]
           (run-is-test "tsers"))))
  (testing "inner expression values are resolved to failure reports"
    (is (= [[{:actual   '(not (= 1 2))
              :expected '(= 1 2)
              :message  nil
              :type     :fail}]
            true]
           (run-is-test (= 1 2)))))
  (testing "retries if asserted expression throws a retryable CUIC exception"
    (let [stale-ex-f (throw-once (ex/retryable-ex "tsers"))]
      (is (= [[{:actual   true
                :expected '(stale-ex-f)
                :message  nil
                :type     :pass}]
              false]
             (run-is-test (stale-ex-f))))))
  (testing "other errors are marked as :error type"
    (let [js-ex-f (throw-once (ex/js-execution-ex "TypeError"))]
      (is (= [[{:actual   "JavaScript evaluation error: TypeError"
                :expected '(js-ex-f)
                :message  nil
                :type     :error}]
              true]
             (-> (run-is-test (js-ex-f))
                 (update-in [0 0 :actual] #(.getMessage %)))))))
  (testing "timeout failure is reported even though thrown exception is retryable"
    (is (= [[{:actual   '(not (throw (ex/retryable-ex "tsers")))
              :expected '(throw (ex/retryable-ex "tsers"))
              :message  nil
              :type     :fail}]
            true]
           (run-is-test (throw (ex/retryable-ex "tsers")))))))

(deftest snapshot-matching
  (if (.exists (io/file snapshot-dir))
    (FileUtils/forceDelete (io/file snapshot-dir)))
  (testing "new snapshot file is created if none exist before assertion"
    (is (matches-snapshot? ::test-1 {:foo "bar" :lol "bal"}))
    (is (snapshot-exists? #"test_1.expected.edn$"))
    (is (not (snapshot-exists? #"test_1.actual.edn$"))))
  (testing "same structurally equivalent value passes the test"
    (is (matches-snapshot? ::test-1 {:lol "bal" :foo "bar"}))
    (is (not (snapshot-exists? #"test_1.actual.edn$"))))
  (testing "different value fails the test"
    (is (not (matches-snapshot? ::test-1 {:lol "bal" :foo "tsers"})))
    (is (snapshot-exists? #"test_1.actual.edn$")))
  (testing "actual file is deleted if test passes again"
    (is (matches-snapshot? ::test-1 {:lol "bal" :foo "bar"}))
    (is (not (snapshot-exists? #"test_1.actual.edn$")))))
