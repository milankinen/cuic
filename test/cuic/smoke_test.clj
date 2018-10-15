(ns cuic.smoke-test
  (:require [clojure.test :refer [deftest testing use-fixtures]]
            [todomvc-server :refer [start-server!]]
            [cuic.test :refer [is]]
            [cuic.core :as c]))

(def headless? true)

(defn ui-test-suite-fixture [test]
  (with-open [_       (start-server! 5000)
              browser (c/launch! {:window-size {:width 1000 :height 1000} :headless headless?})]
    (binding [c/*browser* browser
              c/*config*  (assoc c/*config* :typing-speed :tycitys)]
      (test))))

(defn ui-test-case-fixture [test]
  (c/goto! "http://localhost:5000")
  (test))

(use-fixtures :once ui-test-suite-fixture)
(use-fixtures :each ui-test-case-fixture)

(defn new-todo-input []
  (c/q "#new-todo"))

(defn clear-completed-button []
  (c/q "#clear-completed"))

(defn todo-items []
  (c/q "#todo-list > li"))

(defn todo-text [todo]
  (c/text-content todo))

(defn todo-completed? [todo]
  (c/has-class? todo "completed"))

(defn todo-complete-toggle [todo]
  (c/q todo ".toggle"))

(defn todo-by-text [text]
  (->> (todo-items)
       (filter #(= text (todo-text %)))
       (first)))

(defn add-todo! [text]
  (doto (new-todo-input)
    (c/clear-text!)
    (c/type! text :enter)))

(defn remove-todo! [todo]
  (c/hover! todo)
  (-> (c/q todo "button.destroy")
      (c/click!)))

(defn toggle-complete-status! [todo]
  (c/click! (todo-complete-toggle todo)))

(deftest adding-new-todo
  (testing "new todo items are displayed on the list"
    (is (= (empty? (todo-items))))
    (c/with-retry
      (add-todo! "lol")
      (add-todo! "bal"))
    (is (= ["lol" "bal"] (map todo-text (todo-items))))))

(deftest removing-todo
  (testing "todo is removed from the list"
    (c/with-retry
      (add-todo! "lol")
      (add-todo! "bal"))
    (is (= 2 (count (todo-items))))
    (c/with-retry (remove-todo! (first (todo-items))))
    (is (= 1 (count (todo-items))))))

(deftest marking-todo-as-completed-and-removing-it
  (testing "completed todos can be removed by clicking 'clear completed' button"
    (c/with-retry
      (add-todo! "lol")
      (add-todo! "bal")
      (add-todo! "foo"))
    (is (= [false false false] (map todo-completed? (todo-items))))
    (is (not (c/visible? (clear-completed-button))))
    (c/with-retry (toggle-complete-status! (todo-by-text "bal")))
    (is (= [false true false] (map todo-completed? (todo-items))))
    (is (c/visible? (clear-completed-button)))
    (c/with-retry (c/click! (clear-completed-button)))
    (is (= ["lol" "foo"] (map todo-text (todo-items))))))
