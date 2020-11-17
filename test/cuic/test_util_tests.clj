(ns cuic.test-util-tests
  (:require [clojure.test :refer :all]
            [clojure.string :as string]
            [clojure.java.io :as io]
            [cuic.core :as c]
            [cuic.chrome :as chrome]
            [cuic.test :refer [deftest* is* *screenshot-options* *abort-immediately*]]
            [test-common :refer [todos-url]])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.io File)))

(def ^:dynamic *assertions* nil)

(deftest* assertion-bailout-test-template
  ;; Perform "test" only if assertions is bound so that we can call
  ;; it from test-macro-tests and just skip testing by runner
  (when *assertions*
    (binding [c/*timeout* 100]
      (swap! *assertions* inc)
      (is* false)
      (swap! *assertions* inc)
      (is* true)
      (swap! *assertions* inc))))

(defn- ^File create-temp-dir [prefix]
  (.toFile (Files/createTempDirectory prefix (into-array FileAttribute []))))

(defn- delete-temp-dir [^File dir]
  (doseq [file (reverse (file-seq dir))]
    (try
      (.delete ^File file)
      (catch Exception _))))

(deftest test-macro-tests
  (testing "must be used inside deftest*"
    (is (thrown-with-msg?
          AssertionError
          #"cuic.test/is\* can't be used outside of cuic.test/deftest\*"
          (is* true))))
  (testing "aborts test execution immediately after failure"
    (binding [*assertions* (atom 0)]
      (with-redefs [do-report (constantly nil)]
        (assertion-bailout-test-template))
      (is (= 1 @*assertions*))))
  (testing "test aborting can be disabled"
    (binding [*assertions* (atom 0)
              *abort-immediately* false]
      (with-redefs [do-report (constantly nil)]
        (assertion-bailout-test-template))
      (is (= 3 @*assertions*))))
  (testing "takes screenshot if browser is available"
    (let [screenshots-dir (create-temp-dir "cuic-screenshots")]
      (try
        (with-open [chrome (chrome/launch)]
          (c/goto todos-url {:browser chrome})
          (binding [c/*browser* chrome
                    *assertions* (atom 0)
                    *screenshot-options* {:format  :png
                                          :dir     screenshots-dir
                                          :timeout 10000}]
            (let [reports (atom [])]
              (with-redefs [do-report #(swap! reports conj %)]
                (assertion-bailout-test-template))
              (let [screenshot-report (first (filter (comp #{:cuic/screenshot-taken} :type) @reports))]
                (is (some? screenshot-report))
                (is (.exists (io/file (:filename screenshot-report))))
                (is (string/starts-with? (:filename screenshot-report) (.getAbsolutePath screenshots-dir)))))))
        (finally
          (delete-temp-dir screenshots-dir))))))