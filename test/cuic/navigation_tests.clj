(ns cuic.navigation-tests
  (:require [clojure.test :refer :all]
            [cuic.core :as c]
            [cuic.test :refer [deftest* is* browser-test-fixture]]
            [test-common :refer [forms-test-fixture]]))

(use-fixtures
  :once
  (browser-test-fixture)
  (forms-test-fixture))

(defn- title []
  (c/eval-js "this.title" {} (c/document)))

(deftest* go-forward-backward-tests
  (testing "go-back returns boolean whether going back was possible or not"
    (is* (= "Forms test" (title)))
    (is (true? (c/go-back)))
    (is* (= "" (title)))
    (is (false? (c/go-back))))
  (testing "go-forward returns boolean whether going forward was possible or not"
    (is (true? (c/go-forward)))
    (is* (= "Forms test" (title)))
    (is (false? (c/go-forward)))
    (is* (= "Forms test" (title))))
  (testing "goto reloads whole page"
    (c/click (c/find "#delayed-node-trigger"))
    (is* (some? (c/query "#delayed-node")))
    (c/goto (c/eval-js "this.location.href"))
    (is* (nil? (c/query "#delayed-node")))))
