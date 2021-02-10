(ns cuic.todomvc-smoke-tests
  (:require [clojure.test :refer :all]
            [clojure.string :as string]
            [cuic.core :as c]
            [cuic.test :refer [deftest* is* browser-test-fixture]]
            [test-common :refer [todos-url]]))

(use-fixtures
  :each
  (browser-test-fixture))

(defn todos []
  (c/query ".todo-list li"))

(defn todo-text [todo]
  (string/trim (c/text-content todo)))

(defn add-todo [text]
  (doto (c/find ".new-todo")
    (c/fill text))
  (c/press 'Tab))

(deftest* creating-new-todos
  (c/goto todos-url)
  (is* (= [] (map todo-text (todos))))
  (add-todo "Hello world!")
  (is* (= ["Hello world!"]
          (map todo-text (todos))))
  (add-todo "Tsers!")
  (is* (= ["Hello world!" "Tsers!"]
          (map todo-text (todos)))))

(deftest* editing-todo
  (c/goto todos-url)
  (add-todo "Hello world!")
  (add-todo "Tsers!")
  (c/in (second (todos))
    (c/double-click (c/find "label"))
    (doto (c/find ".edit")
      (c/fill "Lolz"))
    (c/press 'Tab))
  (is* (= ["Hello world!" "Lolz"]
          (map todo-text (todos)))))