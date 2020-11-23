(ns cuic.js-evaluation-tests
  (:require [clojure.test :refer :all]
            [cuic.core :as c]
            [cuic.test :refer [deftest* is* browser-test-fixture]]
            [test-common :refer [forms-test-fixture]]
            [clojure.string :as string])
  (:import (cuic CuicException)))

(use-fixtures
  :once
  (browser-test-fixture)
  (forms-test-fixture))

(deftest* eval-js-tests
  (testing "by default, window of the current browser is used as 'this'"
    (is* (-> (c/eval-js "this.location.href")
             (string/ends-with? "forms.html"))))
  (testing "'this' can be overrided"
    (is* (= "Forms test" (c/eval-js "this.title" {} (c/document)))))
  (testing "evaluated code can be parametrized"
    (is* (= "?Tsers!" (c/eval-js "chars[0] + msg.text + chars[1]"
                                 {:chars ["?" "!"]
                                  :msg   {:text "Tsers"}}))))
  (testing "arguments must be primitive values or objects/arrays"
    (is (thrown-with-msg?
          CuicException
          #"Only JSON primitive values, maps and collections accepted as call argument values but got.+"
          (c/eval-js "123" {:msg (atom 1)}))))
  (testing "code must be syntactically valid js expression"
    (is (thrown-with-msg?
          CuicException
          #"JavaScript error occurred.+"
          (c/eval-js "return 123"))))
  (testing "runtime exceptions are wrapped as CuicExceptions"
    (is (thrown-with-msg?
          CuicException
          #"JavaScript error occurred.+"
          (c/eval-js "this.foo.bar.lol"))))
  (testing "awaiting promised values is supported"
    (is* (= 123 (c/eval-js "await (new Promise(r => setTimeout(() => r(123), 100)))")))))

(deftest* exec-js-tests
  (testing "by default, window of the current browser is used as 'this'"
    (is* (-> (c/exec-js "return this.location.href")
             (string/ends-with? "forms.html"))))
  (testing "'this' can be overrided"
    (is* (= "Forms test" (c/exec-js "return this.title" {} (c/document)))))
  (testing "returns nil if nothing is explicitly returned from body"
    (is* (nil? (c/exec-js "this.location.href"))))
  (testing "executed code can be parametrized"
    (is* (= "?Tsers!" (c/exec-js "return chars[0] + msg.text + chars[1]"
                                 {:chars ["?" "!"]
                                  :msg   {:text "Tsers"}}))))
  (testing "arguments must be primitive values or objects/arrays"
    (is (thrown-with-msg?
          CuicException
          #"Only JSON primitive values, maps and collections accepted as call argument values but got.+"
          (c/exec-js "123" {:msg (atom 1)}))))
  (testing "code must be syntactically valid js code block"
    (is (thrown-with-msg?
          CuicException
          #"JavaScript error occurred.+"
          (c/exec-js "return if else"))))
  (testing "runtime exceptions are wrapped as CuicExceptions"
    (is (thrown-with-msg?
          CuicException
          #"JavaScript error occurred.+"
          (c/exec-js "return this.foo.bar.lol"))))
  (testing "awaiting promised values is supported"
    (is* (= 123 (c/eval-js "await (new Promise(r => setTimeout(() => r(123), 100)))")))))
