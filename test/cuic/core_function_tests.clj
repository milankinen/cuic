(ns cuic.core-function-tests
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [cuic.core :as c]
            [cuic.test :refer [deftest* is* browser-test-fixture]])
  (:import (java.io File)))

(use-fixtures
  :once
  (browser-test-fixture))

(use-fixtures
  :each
  (fn [t]
    (let [url (str "file://" (.getAbsolutePath ^File (io/file "test/resources/forms.html")))]
      (c/goto url)
      (t))))

(deftest* implicit-visibility-waiting-test
  (c/click (c/find "#lazy button"))
  (c/fill (c/find "#lazy input") "tsers!"))

