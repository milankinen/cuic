(ns cuic.input
  (:require [cuic.core :refer [run-mutation current-browser *config*]]
            [cuic.impl.input :as input]))

(defn type!
  "Types the given keys and text with the keyboard"
  [& keys]
  (run-mutation
    (input/type! (current-browser) keys (:typing-speed *config*))))

(defn keyup!
  "Triggers key-up keyboard event"
  [key]
  (run-mutation
    (input/keyup! (current-browser) key)))

(defn keydown!
  "Triggers key-down keyboard event"
  [key]
  (run-mutation
    (input/keydown! (current-browser) key)))

(defn move-mouse!
  "Moves mouse to the given (x,y) coordinate"
  [x y]
  {:pre [(number? x)
         (number? y)]}
  (run-mutation
    (input/mouse-move! (current-browser) {:x x :y y})))

(defn click!
  "Moves mouse to the given (x,y) coordinate and then clicks that position"
  [x y]
  {:pre [(number? x)
         (number? y)]}
  (run-mutation
    (input/mouse-click! (current-browser) {:x x :y y})))
