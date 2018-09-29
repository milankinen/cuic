(ns cuic.impl.input
  (:require [clojure.string :as string]
            [clojure.set :refer [map-invert]]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [clj-chrome-devtools.commands.input :as input]
            [cuic.impl.browser :refer [c]]
            [cuic.impl.ws-invocation :refer [call]])
  (:import (clojure.lang Symbol)))

(defn- to-int [num]
  (if (integer? num) num (Math/round (double num))))

(defonce ^:private layout
  (json/parse-string (slurp (io/resource "us_keyboard_layout.json"))))

(defonce ^:private active-modifiers
  (atom #{}))

(def ^:private aliases
  {'ctrl  'control
   'cmd   'meta
   'up    'arrow-up
   'right 'arrow-right
   'left  'arrow-left
   'down  'arrow-down})

(def ^:private modifier-bits
  {'alt     1
   'control 2
   'meta    4
   'shift   8})

(def ^:private key->event
  (->> (into {} (map (fn [[_ m]] [(get m "key") m]) layout))
       (merge layout)
       (map (fn [[k m]] [k {:key                      (get m "key")
                            :native-virtual-key-code  (get m "keyCode")
                            :windows-virtual-key-code (get m "keyCode")
                            :code                     (get m "code")}]))
       (into {})))

(defn- resolve-aliases [xs]
  (map #(if (contains? aliases %) (with-meta (get aliases %) (meta %)) %) xs))

(defn- current-modifier-bits [browser]
  (->> (filter #(= browser (first %)) @active-modifiers)
       (map (comp modifier-bits second))
       (reduce bit-or 0)))

(defn- sym->event [sym]
  (-> (string/capitalize (name sym))
      (string/replace #"-(.)" (comp string/upper-case second))
      (key->event)))

(defn- get-modifiers [sym]
  (if (symbol? sym)
    (->> (filter (comp true? second) (meta sym))
         (map (comp symbol name first))
         (resolve-aliases)
         (set))
    #{}))

(defn- validate-modifiers [key]
  (doseq [m (get-modifiers key)]
    (if-not (contains? modifier-bits m)
      (throw (IllegalArgumentException. (str "Invalid modifier: " m))))))

(defn- validate-key-name [key]
  (if (and (symbol? key)
           (not (sym->event key)))
    (throw (IllegalArgumentException. (str "Invalid key: " key)))))

(defn- dispatch-event! [browser event-type sym]
  (as-> (sym->event sym) $
        (assoc $ :type event-type
                 :modifiers (current-modifier-bits browser))
        (call input/dispatch-key-event (c browser) $)))

(defn- -key-down! [browser sym]
  (dispatch-event! browser "rawKeyDown" sym)
  (if (contains? modifier-bits sym)
    (swap! active-modifiers conj [browser sym])))

(defn- -key-up! [browser sym]
  (if (contains? modifier-bits sym)
    (swap! active-modifiers disj [browser sym]))
  (dispatch-event! browser "keyUp" sym))

(defmulti press-key! (fn [_ key] (type key)))

(defmethod press-key! Character [browser ch]
  (case (str ch)
    "\n" (press-key! browser 'enter)
    "\t" (press-key! browser 'tab)
    "\r" nil
    (call input/dispatch-key-event
          (c browser)
          {:type            "char"
           :text            ch
           :key             ch
           :unmodified-text ch
           :modifiers       (current-modifier-bits browser)})))

(defmethod press-key! Symbol [browser sym]
  (doseq [m (get-modifiers sym)]
    (-key-down! browser m))
  (-key-down! browser sym)
  (-key-up! browser sym)
  (doseq [m (get-modifiers sym)]
    (-key-up! browser m)))

(defmethod press-key! :default [_ _ k]
  (throw (IllegalArgumentException. (str "Invalid key: " k))))

(defn- flatten-key [k]
  (letfn [(sym-with-modifiers [xs]
            (->> (drop-last 1 xs)
                 (map #(vector % true))
                 (into {})
                 (with-meta (symbol (name (last xs))))))]
    (cond
      (keyword? k) [(symbol (name k))]
      (string? k) (seq k)
      (and (vector? k) (keyword? (last k))) [(sym-with-modifiers k)]
      :else [k])))

(defn type! [browser keys-to-type speed]
  (let [resolved-keys (->> (mapcat flatten-key keys-to-type)
                           (resolve-aliases))]
    (doseq [key resolved-keys]
      (validate-key-name key)
      (validate-modifiers key))
    (doseq [key resolved-keys]
      (press-key! browser key)
      (Thread/sleep (case speed
                      :tycitys 5
                      :fast 25
                      :slow 100
                      50)))))

(defn keyup! [browser key]
  (let [k (->> (mapcat flatten-key [key])
               (resolve-aliases)
               (first))]
    (validate-key-name k)
    (validate-modifiers k)
    (-key-up! browser key)))

(defn keydown! [browser key]
  (let [k (->> (mapcat flatten-key [key])
               (resolve-aliases)
               (first))]
    (validate-key-name k)
    (validate-modifiers k)
    (-key-down! browser key)))

(defn mouse-move! [browser {:keys [x y]}]
  (call input/dispatch-mouse-event
        (c browser)
        {:x           (to-int x)
         :y           (to-int y)
         :type        "mouseMoved"
         :click-count 1
         :button      "left"}))

(defn mouse-click! [browser {:keys [x y] :as point}]
  (let [ev {:x           (to-int x)
            :y           (to-int y)
            :click-count 1
            :button      "left"}]
    (mouse-move! browser point)
    (call input/dispatch-mouse-event (c browser) (assoc ev :type "mousePressed"))
    (call input/dispatch-mouse-event (c browser) (assoc ev :type "mouseReleased"))))


