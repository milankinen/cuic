(ns cuic.keyboard
  (:require [cuic.impl.input :as input]
            [cuic.core :as core :refer [-browser]]))

(defn type! [& keys]
  (core/-run-mutation
    (input/type! (-browser) keys (:typing-speed core/*config*))))

(defn keyup! [key]
  (core/-run-mutation
    (input/keyup! (-browser) key)))

(defn keydown! [key]
  (core/-run-mutation
    (input/keydown! (-browser) key)))