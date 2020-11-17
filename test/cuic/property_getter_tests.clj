(ns cuic.property-getter-tests
  (:require [clojure.test :refer :all]
            [cuic.core :as c]
            [cuic.test :refer [deftest* is* browser-test-fixture]]
            [test-common :refer [forms-url todos-url]])
  (:import (cuic CuicException)))

(use-fixtures
  :once
  (browser-test-fixture))

(deftest* basic-property-getter-tests
  (c/goto todos-url)
  (c/wait 1000)
  (is (= #{"header" "desktop"} (c/classes (c/find ".header"))))
  (is (= {:autofocus   true
          :class       "new-todo"
          :placeholder "What needs to be done?"}
         (c/attributes (c/find ".new-todo"))))
  (c/click (c/find ".new-todo"))
  (is* (c/has-focus? (c/find ".new-todo")))
  (is (c/visible? (c/find ".new-todo")))
  (is (not (c/visible? (c/find ".main"))))
  (let [{:keys [top left width height]} (c/client-rect (c/find ".new-todo"))]
    (is (pos? top))
    (is (pos? left))
    (is (pos? width))
    (is (pos? height)))
  (is (= "" (c/value (c/find ".new-todo"))))
  (is (c/has-class? (c/find ".header") "desktop"))
  (is (not (c/has-class? (c/find ".header") "mobile")))
  (is (c/matches? (c/find ".header") ".desktop"))
  (is (not (c/matches? (c/find ".header") ".mobile")))
  (is (thrown-with-msg?
        CuicException
        #"The tested css selector .+ is not valid"
        (c/matches? (c/find ".header") "not-valdi<<>")))
  (is (= "Double-click to edit a todo\n\nWritten by Luke Edwards\n\nRefactored by Aaron Muir Hamilton\n\nPart of TodoMVC" (c/inner-text (c/find ".info"))))
  (is (= "\n  Double-click to edit a todo\n  Written by Luke Edwards\n  Refactored by Aaron Muir Hamilton\n  Part of TodoMVC\n" (c/text-content (c/find ".info"))))
  (is (= [:header {:class "header desktop"}
          [:h1 {} "todos"]
          [:input {:autofocus   true
                   :class       "new-todo"
                   :placeholder "What needs to be done?"}]]
         (c/outer-html (c/find ".header"))))
  ;; Sanity checks for special cases that parsing won't fail
  (let [body (c/outer-html (c/find "body"))
        head (c/outer-html (c/find "head"))
        html (c/outer-html (c/document))]
    (is (vector? body))
    (is (vector? head))
    (is (= :body (first body)))
    (is (= :head (first head)))
    (is (= [:html {:data-framework "es6"
                   :lang           "en"}
            head
            body]
           html)))
  (c/goto forms-url)
  (is (= [{:selected true
           :text     "Foo"
           :value    "f"}
          {:selected false
           :text     "Bar"
           :value    "b"}
          {:selected false
           :text     "Tsers"
           :value    "t"}]
         (c/options (c/find "#select"))))
  (is (c/checked? (c/find "#checked")))
  (is (not (c/checked? (c/find "#unchecked"))))
  (is (c/disabled? (c/find "#textarea")))
  (is (not (c/disabled? (c/find "#input")))))

(deftest* node-gets-removed-between-query-and-getter
  (c/goto forms-url)
  (let [rm-btn (c/find "#remove-input")
        input (c/find "#input-to-remove")]
    (dotimes [_ 5]
      (is (= "..." (c/value input))))
    (dotimes [_ 5]
      (is (map? (c/attributes input))))
    (c/click rm-btn)
    (is* (c/disabled? rm-btn))
    (testing "property obtained via js execution"
      (is (thrown-with-msg?
            CuicException
            #"Can't get value from .+ because node does not exist anymore"
            (c/value input))))
    (testing "property obtained via cdt call"
      (is (thrown-with-msg?
            CuicException
            #"Can't get attributes from .+ because node does not exist anymore"
            (c/attributes input))))))
