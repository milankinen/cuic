(ns cuic-examples.todomvc-tests-without-local-server
  (:require [clojure.test :refer :all]
            [cuic.core :as c]
            [cuic.test :refer [deftest* is* browser-test-fixture]]))

(use-fixtures
  :once
  (browser-test-fixture))

(defn todos []
  (->> (c/query ".todo-list li")
       (map c/text-content)))

(defn add-todo [text]
  (doto (c/find ".new-todo")
    (c/fill text))
  (c/press 'Enter))

(deftest* creating-new-todos
  (c/goto "http://todomvc.com/examples/react")
  (is* (= [] (todos)))
  (add-todo "Hello world!")
  (is* (= ["Hello world!"] (todos)))
  (add-todo "Tsers!")
  (is* (= ["Hello world!" "Tsers!"] (todos))))
