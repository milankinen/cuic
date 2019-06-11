(ns cuic.input
  (:require [cuic.core :refer [browser *typing-speed*]]
            [cuic.util :refer [mutation]]
            [cuic.impl.input :as impl]))

(defn type!
  "Types the given keys and text with the keyboard"
  [& keys]
  (mutation (impl/type! (browser) keys *typing-speed*)))

(defn keyup!
  "Triggers key-up keyboard event"
  [key]
  (mutation (impl/keyup! (browser) key)))

(defn keydown!
  "Triggers key-down keyboard event"
  [key]
  (mutation (impl/keydown! (browser) key)))

(defn move-mouse!
  "Moves mouse to the given (x,y) coordinate"
  [x y]
  {:pre [(number? x)
         (number? y)]}
  (mutation (impl/mouse-move! (browser) {:x x :y y})))

(defn click!
  "Moves mouse to the given (x,y) coordinate and then clicks that position"
  [x y]
  {:pre [(number? x)
         (number? y)]}
  (mutation (impl/mouse-click! (browser) {:x x :y y})))
