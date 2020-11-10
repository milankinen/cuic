(ns cuic.core-query-tests
  (:require [clojure.test :refer :all]
            [cuic.core :as c]
            [cuic.test :refer [browser-test-fixture]]
            [cuic.test-common :refer [forms-test-fixture]])
  (:import (cuic CuicException)))

(use-fixtures
  :once
  (browser-test-fixture)
  (forms-test-fixture))

(deftest find-tests
  (binding [c/*timeout* 1000]
    (testing "implicit wait until node is found from dom"
      (is (some? (c/find "#delayed-node-trigger")))
      (is (thrown? CuicException (c/find "#delayed-node")))
      (c/click (c/find "#delayed-node-trigger"))
      (is (some? (c/find "#delayed-node"))))
    (testing "node naming and error messages"
      (is (thrown-with-msg?
            CuicException
            #"Could not find node from \"document\" with selector \".non-existing\" in 1000 milliseconds"
            (c/find ".non-existing")))
      (is (thrown-with-msg?
            CuicException
            #"Could not find node \"MyNode\" from \"document\" with selector \".non-existing\" in 1000 milliseconds"
            (c/find {:by ".non-existing"
                     :as "MyNode"})))))
  (binding [c/*timeout* 100]
    (testing "finding under context node"
      (let [ctx-1 (c/find "#context-1")
            ctx-2 (c/find "#context-2")
            named-ctx-2 (c/find {:by "#context-2"
                                 :as "Context"})]
        (is (some? (c/find {:by "#hello" :from ctx-1})))
        (is (some? (c/in ctx-1 (c/find "#hello"))))
        (is (thrown-with-msg?
              CuicException
              #"Could not find node from \"#context-2\" with selector \"#hello\" in \d+ milliseconds"
              (c/find {:by "#hello" :from ctx-2})))
        (is (thrown-with-msg?
              CuicException
              #"Could not find node from \"#context-2\" with selector \"#hello\" in \d+ milliseconds"
              (c/in ctx-2 (c/find "#hello"))))
        (is (thrown-with-msg?
              CuicException
              #"Could not find node from \"Context\" with selector \"#hello\" in \d+ milliseconds"
              (c/in named-ctx-2 (c/find "#hello"))))))))

