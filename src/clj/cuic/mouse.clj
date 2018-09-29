(ns cuic.mouse
  (:require [cuic.impl.input :as input]
            [cuic.core :refer [-run-mutation -browser]]))

(defn move! [x y]
  {:pre [(number? x)
         (number? y)]}
  (-run-mutation
    (input/mouse-move! (-browser) {:x x :y y})))

(defn click! [x y]
  {:pre [(number? x)
         (number? y)]}
  (-run-mutation
    (input/mouse-click! (-browser) {:x x :y y})))
