(ns cuic.forms-test
  (:require [clojure.test :refer :all]
            [cuic.core :as c]
            [cuic.test :refer [is* matches-snapshot?]]
            [clojure.java.io :as io]))


(defn form-test-fixture [t]
  (with-open [b (c/launch! {:window-size {:width 1000 :height 1000}})]
    (binding [c/*browser* b]
      (c/goto! (str "file://" (.getAbsolutePath (io/file "test/forms/index.html"))))
      (t))))

(use-fixtures :each form-test-fixture)

(deftest form-editing-and-inspection
  (testing "selecting and viewing <select> options works"
    (let [select #(c/q "#select")]
      (is* (matches-snapshot? ::select-options-before (c/options (select))))
      (c/select! (select) "t")
      (is* (matches-snapshot? ::select-options-after (c/options (select))))))
  (testing "basic typing works"
    (let [input    #(c/q "input[type='text']")
          textarea #(c/q "textarea")]
      (is* (= "lolbal" (c/value (input))))
      (is* (= "Tsers!" (c/value (textarea))))
      (doto (input)
        (c/clear-text!)
        (c/type! "no huomenta päivää"))
      (doto (textarea)
        (c/clear-text!)
        (c/type! "tsers" :enter "piste" :enter "fi"))
      (is* (= "no huomenta päivää" (c/value (input))))
      (is* (= "tsers\npiste\nfi" (c/value (textarea))))))
  (testing "checkbox toggling works"
    (let [cb #(c/q "input[type='checkbox']")]
      (is* (false? (c/checked? (cb))))
      (c/click! (cb))
      (is* (true? (c/checked? (cb))))
      (c/click! (cb))
      (is* (false? (c/checked? (cb))))))
  (testing "radio button toggling works"
    (let [selection #(->> (c/q "input[type='radio']")
                          (filter c/checked?)
                          (map c/value)
                          (first))]
      (is* (nil? (selection)))
      (c/click! (first (c/q "input[type='radio']")))
      (is* (= "a" (selection)))
      (c/click! (second (c/q "input[type='radio']")))
      (is* (= "b" (selection))))))


