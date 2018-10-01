(ns cuic.smoke-test
  (:require [clojure.test :refer [deftest testing use-fixtures]]
            [todomvc-server :refer [start-server!]]
            [cuic.test :refer [is]]
            [cuic.core :as t]))

(def headless? true)

(defn ui-test-suite-fixture [test]
  (with-open [_       (start-server! 5000)
              browser (t/launch! {:window-size {:width 1000 :height 1000} :headless headless?})]
    (binding [t/*browser* browser
              t/*config*  (assoc t/*config* :typing-speed :tycitys)]
      (test))))

(defn ui-test-case-fixture [test]
  (t/goto! "http://localhost:5000")
  (test))

(use-fixtures :once ui-test-suite-fixture)
(use-fixtures :each ui-test-case-fixture)

(defn new-todo-input []
  (t/q "#new-todo"))

(defn clear-completed-button []
  (t/q "#clear-completed"))

(defn todo-items []
  (t/q "#todo-list > li"))

(defn todo-text [todo]
  (t/text-content todo))

(defn todo-completed? [todo]
  (t/has-class? todo "completed"))

(defn todo-complete-toggle [todo]
  (t/q todo ".toggle"))

(defn todo-by-text [text]
  (->> (todo-items)
       (filter #(= text (todo-text %)))
       (first)))

(defn add-todo! [text]
  (doto (new-todo-input)
    (t/clear-text!)
    (t/type! text :enter)))

(defn remove-todo! [todo]
  (t/hover! todo)
  (-> (t/q todo "button.destroy")
      (t/click!)))

(defn toggle-complete-status! [todo]
  (t/click! (todo-complete-toggle todo)))

(deftest adding-new-todo
  (testing "new todo items are displayed on the list"
    (is (= (empty? (todo-items))))
    (t/with-retry
      (add-todo! "lol")
      (add-todo! "bal"))
    (is (= ["lol" "bal"] (map todo-text (todo-items))))))

(deftest removing-todo
  (testing "todo is removed from the list"
    (t/with-retry
      (add-todo! "lol")
      (add-todo! "bal"))
    (is (= 2 (count (todo-items))))
    (t/with-retry (remove-todo! (first (todo-items))))
    (is (= 1 (count (todo-items))))))

(deftest marking-todo-as-completed-and-removing-it
  (testing "completed todos can be removed by clicking 'clear completed' button"
    (t/with-retry
      (add-todo! "lol")
      (add-todo! "bal")
      (add-todo! "foo"))
    (is (= [false false false] (map todo-completed? (todo-items))))
    (is (not (t/visible? (clear-completed-button))))
    (t/with-retry (toggle-complete-status! (todo-by-text "bal")))
    (is (= [false true false] (map todo-completed? (todo-items))))
    (is (t/visible? (clear-completed-button)))
    (t/with-retry (t/click! (clear-completed-button)))
    (is (= ["lol" "foo"] (map todo-text (todo-items))))))
