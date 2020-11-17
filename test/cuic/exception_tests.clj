(ns cuic.exception-tests
  (:require [clojure.test :refer :all]
            [cuic.test :refer [browser-test-fixture]]
            [cuic.internal.util :refer [*hide-internal-stacktrace*]]
            [test-common :refer [forms-test-fixture]]
            [cuic.core :as c]
            [clojure.string :as string])
  (:import (cuic CuicException)))

(use-fixtures
  :once
  (browser-test-fixture)
  (forms-test-fixture))

(deftest stacktrace-rewrite-tests
  (testing "internal stack frames are removed from thrown exceptions"
    (binding [*hide-internal-stacktrace* true]
      (try
        (c/clear-text (c/find "#delayed-node-trigger"))
        (assert (not "Exception was not thrown"))
        (catch CuicException ex
          (is (= "Node \"#delayed-node-trigger\" is not a valid input element"
                 (ex-message ex)))
          (let [st (seq (.getStackTrace ex))]
            (is (string/starts-with? (str (first st)) "cuic.core$clear_text"))))))))

