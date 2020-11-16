(ns cuic.test-common
  (:require [clojure.java.io :as io]
            [cuic.core :as c]
            [cuic.test :refer [browser-test-fixture]])
  (:import (java.io File)))

(def forms-url
  (str "file://" (.getAbsolutePath ^File (io/file "test/resources/forms.html"))))

(def todos-url
  (str "file://" (.getAbsolutePath ^File (io/file "test/resources/todos.html"))))

(defn forms-test-fixture []
  (fn [t]
    (c/goto forms-url)
    (t)))

(def ^:dynamic *secondary-chrome* nil)

(defn multibrowser-fixture []
  (let [f (browser-test-fixture)]
    (fn [t]
      (f (fn []
           (binding [*secondary-chrome* c/*browser*]
             (assert *secondary-chrome*)
             (f t)))))))
