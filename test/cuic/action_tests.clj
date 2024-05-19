(ns cuic.action-tests
  (:require [clojure.test :refer :all]
            [clojure.string :as string]
            [clojure.java.io :as io]
            [cuic.core :as c]
            [cuic.test :refer [deftest* is* browser-test-fixture]]
            [test-common :refer [forms-url]])
  (:import (cuic CuicException TimeoutException)))

(use-fixtures
  :once
  (browser-test-fixture))

(deftest* basic-actions-test
  (let [has-text? #(string/includes? (c/inner-text (c/find "body")) %)]
    (c/goto forms-url)
    (testing "choosing select values"
      (is* (has-text? "Selection: Foo"))
      (-> (c/find "#select")
          (c/select "t"))
      (is* (has-text? "Selection: Tsers"))
      (is* (has-text? "Multiselection: -"))
      (-> (c/find "#multiselect")
          (c/select ["f" "b"]))
      (is* (has-text? "Multiselection: Foo, Bar")))
    (testing "typing text and filling inputs"
      (is* (has-text? "Input value is: lolbal"))
      (-> (c/find "#input")
          (c/focus))
      (c/press 'End)
      (c/type "baz")
      (is* (has-text? "Input value is: lolbalbaz"))
      (-> (c/find "#input")
          (c/fill "tsers"))
      (is* (has-text? "Input value is: tsers"))
      (-> (c/find "#input")
          (c/clear-text))
      (is* (has-text? "Input value is: -"))
      (-> (c/find "#input")
          (c/fill "aaaa" {:speed :tycitys}))
      (is* (has-text? "Input value is: aaaa"))
      (-> (c/find "#input")
          (c/select-all-text))
      (c/type "b")
      (is* (has-text? "Input value is: b")))
    (testing "checking checkboxes and radio buttons"
      (let [checkbox (c/find "#unchecked")
            [radio-a radio-b] (c/query "input[name=rad]")]
        (is* (not (c/checked? checkbox)))
        (c/click checkbox)
        (is* (true? (c/checked? checkbox)))
        (is* (not (c/checked? radio-a)))
        (is* (not (c/checked? radio-b)))
        (c/click radio-a)
        (is* (c/checked? radio-a))
        (is* (not (c/checked? radio-b)))
        (c/click radio-b)
        (is* (not (c/checked? radio-a)))
        (is* (c/checked? radio-b))))
    (let [files [(io/file "test/resources/foo.txt")
                 (io/file "test/resources/bar.txt")]]
      (testing "adding files"
        (c/add-files (c/find "#files") files)
        (is* (has-text? "Added files: bar.txt, foo.txt")))
      (testing "adding hidden files"
        (binding [c/*timeout* 1000]
          (is (thrown? TimeoutException (c/add-files (c/find "#hidden-files") files))))
        (c/add-files (c/find "#hidden-files") files {:allow-hidden? true})
        (is* (has-text? "Added hidden files: bar.txt, foo.txt"))))
    (testing "scrolling node into viewport"
      (is (not (c/in-viewport? (c/find "#needs-scrolling-btn"))))
      (c/scroll-into-view (c/find "#needs-scrolling-btn"))
      (is (c/in-viewport? (c/find "#needs-scrolling-btn"))))))

(deftest* dialog-tests
  (c/goto forms-url)
  (let [has-text? #(string/includes? (c/inner-text (c/find "body")) %)
        dialog (atom nil)]
    (c/on-dialog #(do (reset! dialog %) :accept))
    (c/click (c/find "#show-alert"))
    (is* (= {:default-prompt ""
             :message        "tsers!"
             :type           :alert}
            @dialog))
    (c/click (c/find "#show-confirm"))
    (is* (= {:default-prompt ""
             :message        "Sure?"
             :type           :confirm}
            @dialog))
    (is* (has-text? "Confirm result: true"))
    (c/click (c/find "#show-prompt"))
    (is* (= {:default-prompt "tsers"
             :message        "Greeting"
             :type           :prompt}
            @dialog))
    (c/on-dialog #(do (reset! dialog %) :accept))
    (is* (has-text? "Prompt result: tsers"))
    (c/on-dialog (constantly "lolbal"))
    (c/click (c/find "#show-prompt"))
    (is* (has-text? "Prompt result: lolbal"))))

(deftest* click-with-modifiers
  (c/goto forms-url)
  (let [has-text? #(string/includes? (c/inner-text (c/find "#modifiers-test-result")) %)]
    (doseq [[modifiers expected-result]
            [[[] "modifiers: alt=false; meta=false; ctrl=false; shift=false"]
             [["Meta"] "modifiers: alt=false; meta=true; ctrl=false; shift=false"]
             [["Alt"] "modifiers: alt=true; meta=false; ctrl=false; shift=false"]
             ;; on OSX, next line may or may not pop up OS context menu, depending on OS-level settings
             #_[["Ctrl"] "modifiers: alt=false; meta=false; ctrl=true; shift=false"]
             [["Shift"] "modifiers: alt=false; meta=false; ctrl=false; shift=true"]
             [#{"Alt" "Shift"} "modifiers: alt=true; meta=false; ctrl=false; shift=true"]]]
      (c/click (c/find "#modifiers-test") {:modifiers modifiers})
      (is* (has-text? expected-result)))))

(deftest* scroll-into-view-before-action-test
  (c/goto forms-url)
  (is* (not (c/in-viewport? (c/find "#needs-scrolling-btn"))))
  (c/click (c/find "#needs-scrolling-btn"))
  (is* (= "Clicked" (c/inner-text (c/find "#needs-scrolling-result"))))
  (is* (c/in-viewport? (c/find "#needs-scrolling-btn"))))

(deftest* implicit-waiting-until-visible-test
  (testing "node becomes active withing timeout"
    (c/goto forms-url)
    (c/click (c/find "#activate-after-1s-trigger"))
    (c/click (c/find "#input-to-activate"))
    (is* (= "Clicked" (c/inner-text (c/find "#activated-input-result"))))
    (c/goto forms-url)
    (c/click (c/find "#activate-after-1s-trigger"))
    (c/fill (c/find "#input-to-activate") "tsers")
    (is* (= "Typed: tsers" (c/inner-text (c/find "#activated-input-result")))))
  (testing "node does not become active withing timeout"
    (c/goto forms-url)
    (c/click (c/find "#activate-after-1s-trigger"))
    (binding [c/*timeout* 100]
      (is (thrown-with-msg?
            CuicException
            #"Can't fill element .+"
            (c/fill (c/find "#input-to-activate") "tsers"))))))


