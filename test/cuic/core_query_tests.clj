(ns cuic.core-query-tests
  (:require [clojure.test :refer :all]
            [clojure.string :as string]
            [cuic.core :as c]
            [cuic.test :refer [deftest* is*]]
            [test-common :refer [multibrowser-fixture
                                 forms-test-fixture
                                 *secondary-chrome*
                                 todos-url]])
  (:import (cuic CuicException)))

(use-fixtures
  :once
  (multibrowser-fixture))

(use-fixtures
  :each
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
        (is (some? (c/find {:by "#hello" :in ctx-1})))
        (is (some? (c/in ctx-1 (c/find "#hello"))))
        (is (thrown-with-msg?
              CuicException
              #"Could not find node from \"#context-2\" with selector \"#hello\" in \d+ milliseconds"
              (c/find {:by "#hello" :in ctx-2})))
        (is (thrown-with-msg?
              CuicException
              #"Could not find node from \"#context-2\" with selector \"#hello\" in \d+ milliseconds"
              (c/in ctx-2 (c/find "#hello"))))
        (is (thrown-with-msg?
              CuicException
              #"Could not find node from \"Context\" with selector \"#hello\" in \d+ milliseconds"
              (c/in named-ctx-2 (c/find "#hello"))))))))

(deftest query-tests
  (testing "the found nodes are returned without waiting"
    (is (some? (c/find "#delayed-node-trigger")))
    (is (nil? (c/query "#delayed-node")))
    (c/click (c/find "#delayed-node-trigger"))
    (is (c/wait (= 1 (count (c/query "#delayed-node"))))))
  (testing "multiple results are supported"
    (is (vector? (c/query "#select option")))
    (is (= 3 (count (c/query "#select option")))))
  (testing "nodes can be named"
    (is (= ["#node {:tag \"option\", :name \"Option\", :selector \"#select option\"}"
            "#node {:tag \"option\", :name \"Option\", :selector \"#select option\"}"
            "#node {:tag \"option\", :name \"Option\", :selector \"#select option\"}"]
           (map pr-str (c/query {:by "#select option" :as "Option"})))))
  (testing "querying under context node"
    (let [ctx-1 (c/find "#context-1")
          ctx-2 (c/find "#context-2")]
      (is (vector? (c/query {:by "#hello" :in ctx-1})))
      (is (vector? (c/in ctx-1 (c/query "#hello"))))
      (is (nil? (c/query {:by "#hello" :in ctx-2})))
      (is (nil? (c/in ctx-2 (c/query "#hello")))))))

(deftest* document-tests
  (c/goto todos-url {:browser *secondary-chrome*})
  (testing "default browser is used by default"
    (is* (= "Forms test" (c/eval-js "this.title" {} (c/document)))))
  (testing "browser can be overrided"
    (is* (= "TodoMVC" (c/eval-js "this.title" {} (c/document *secondary-chrome*))))))

(deftest* window-tests
  (c/goto todos-url {:browser *secondary-chrome*})
  (testing "default browser is used by default"
    (is* (-> (c/eval-js "this.location.href" {} (c/window))
             (string/ends-with? "forms.html"))))
  (testing "default browser is used by default"
    (is* (-> (c/eval-js "this.location.href" {} (c/window *secondary-chrome*))
             (string/ends-with? "todos.html")))))

(deftest* active-element-tests
  (c/goto todos-url)
  (testing "currently active element is returned"
    (c/click (c/find ".new-todo"))
    (is* (= #{"new-todo"} (c/classes (c/active-element))))))
