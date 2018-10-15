(ns cuic.assertions-test
  (:require [clojure.test :refer [deftest testing use-fixtures is do-report]]
            [cuic.core :as c]
            [cuic.test :as ct]
            [cuic.assertions-test]
            [cuic.impl.exception :as ex]
            [clojure.java.io :as io])
  (:import (org.apache.commons.io FileUtils)
           (javax.imageio ImageIO)))

(defn throw-once [ex]
  (let [thrown (atom false)]
    (fn []
      (when-not @thrown
        (reset! thrown true)
        (throw ex))
      true)))

(def snapshot-dir "target/snapshot")

(use-fixtures :each (fn [t] (binding [c/*config* (assoc c/*config* :timeout 500 :snapshot-dir snapshot-dir)] (t))))

(defmacro run-is-test [expr]
  `(let [reports# (atom [])]
     (with-redefs [do-report #(swap! reports# conj %)]
       (ct/is* ~expr))
     @reports#))

(defn snapshot-exists? [pattern]
  (->> (file-seq (io/file snapshot-dir))
       (filter #(re-find pattern (.getName %)))
       (empty?)
       (not)))

(defn read-image [res]
  (ImageIO/read (io/input-stream (io/resource res))))

(deftest is-macro
  (testing "waits until asserted expression is truthy or fails after certain wait period"
    (is (= [{:actual   true
             :expected true
             :message  nil
             :type     :pass}]
           (run-is-test true)))
    (is (= [{:actual   "tsers"
             :expected "tsers"
             :message  nil
             :type     :pass}]
           (run-is-test "tsers"))))
  (testing "inner expression values are resolved to failure reports"
    (is (= [{:actual   '(not (= 1 2))
             :expected '(= 1 2)
             :message  nil
             :type     :fail}]
           (run-is-test (= 1 2)))))
  (testing "retries if asserted expression throws a retryable CUIC exception"
    (let [stale-ex-f (throw-once (ex/stale-node (Exception. "tsers")))]
      (is (= [{:actual   true
               :expected '(stale-ex-f)
               :message  nil
               :type     :pass}]
             (run-is-test (stale-ex-f))))))
  (testing "other errors are marked as :error type"
    (let [js-ex-f (throw-once (ex/js-error "TypeError"))]
      (is (= [{:actual   "JavaScript error"
               :expected '(js-ex-f)
               :message  nil
               :type     :error}]
             (-> (run-is-test (js-ex-f))
                 (update-in [0 :actual] #(.getMessage %)))))))
  (testing "latest error is used as actual for error reporting if assertion timeouts because of exception"
    (is (= [{:actual   "Node not found from DOM"
             :expected '(throw (ex/stale-node (Exception. "tsers")))
             :message  nil
             :type     :error}]
           (-> (run-is-test (throw (ex/stale-node (Exception. "tsers"))))
               (update-in [0 :actual] #(.getMessage %)))))))

(deftest snapshot-matching
  (if (.exists (io/file snapshot-dir))
    (FileUtils/forceDelete (io/file snapshot-dir)))
  (testing "new snapshot file is created if none exist before assertion"
    (is (ct/matches-snapshot? ::test-1 {:foo "bar" :lol "bal"}))
    (is (snapshot-exists? #"test_1.expected.edn$"))
    (is (not (snapshot-exists? #"test_1.actual.edn$"))))
  (testing "same structurally equivalent value passes the test"
    (is (ct/matches-snapshot? ::test-1 {:lol "bal" :foo "bar"}))
    (is (not (snapshot-exists? #"test_1.actual.edn$"))))
  (testing "different value fails the test"
    (is (not (ct/matches-snapshot? ::test-1 {:lol "bal" :foo "tsers"})))
    (is (snapshot-exists? #"test_1.actual.edn$")))
  (testing "actual file is deleted if test passes again"
    (is (ct/matches-snapshot? ::test-1 {:lol "bal" :foo "bar"}))
    (is (not (snapshot-exists? #"test_1.actual.edn$")))))
