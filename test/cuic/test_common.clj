(ns cuic.test-common
  (:require [clojure.java.io :as io]
            [cuic.core :as c])
  (:import (java.io File)))

(def forms-html-url
  (str "file://" (.getAbsolutePath ^File (io/file "test/resources/forms.html"))))

(defn forms-test-fixture []
  (fn [t]
    (c/goto forms-html-url)
    (t)))