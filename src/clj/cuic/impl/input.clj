(ns cuic.impl.input
  (:require [clojure.string :as string]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [cuic.impl.exception :refer [call]]
            [cuic.impl.browser :refer [tools]])
  (:import (clojure.lang Symbol)
           (com.github.kklisura.cdt.protocol.types.input DispatchMouseEventType DispatchMouseEventButton DispatchKeyEventType)))

(defonce ^:private layout
  (json/read-str (slurp (io/resource "us_keyboard_layout.json"))))

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

(defn- dispatch-kb-event! [browser {:keys [type modifiers key code key-identifier native-virtual-key-code
                                           windows-virtual-key-code text unmodified-text] :as ev}]
  (call #(-> (.getInput (tools browser))
             (.dispatchKeyEvent
               type
               modifiers
               nil
               text
               unmodified-text
               key-identifier
               code
               key
               (some-> windows-virtual-key-code (int))
               (some-> native-virtual-key-code (int))
               nil                                          ; autorepeat?
               nil                                          ; is keypad?
               nil                                          ; is system key?
               nil                                          ; location
               ))))

(defn- dispatch-mouse-event! [browser {:keys [x y type modifiers click-count button delta-x delta-y]}]
  (call #(-> (.getInput (tools browser))
             (.dispatchMouseEvent
               type
               (double x)
               (double y)
               modifiers
               nil                                          ; timestamp
               button
               (some-> click-count (int))
               (some-> delta-x (double))
               (some-> delta-y (double))))))

(defn- resolve-aliases [xs]
  (map #(if (contains? aliases %) (with-meta (get aliases %) (meta %)) %) xs))

(defn- current-modifier-bits [browser]
  (->> (filter #(= browser (first %)) @active-modifiers)
       (map (comp modifier-bits second))
       (reduce bit-or 0)
       (int)))

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
        (dispatch-kb-event! browser $)))

(defn- -key-down! [browser sym]
  (dispatch-event! browser DispatchKeyEventType/RAW_KEY_DOWN sym)
  (if (contains? modifier-bits sym)
    (swap! active-modifiers conj [browser sym])))

(defn- -key-up! [browser sym]
  (if (contains? modifier-bits sym)
    (swap! active-modifiers disj [browser sym]))
  (dispatch-event! browser DispatchKeyEventType/KEY_UP sym))

(defmulti press-key! (fn [_ key] (type key)))

(defmethod press-key! Character [browser ch]
  (case (str ch)
    "\n" (press-key! browser 'enter)
    "\t" (press-key! browser 'tab)
    "\r" nil
    (dispatch-kb-event!
      browser
      {:type            DispatchKeyEventType/CHAR
       :text            (str ch)
       :key             (str ch)
       :unmodified-text (str ch)
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
  (dispatch-mouse-event!
    browser
    {:x           x
     :y           y
     :type        DispatchMouseEventType/MOUSE_MOVED
     :click-count 1
     :button      DispatchMouseEventButton/LEFT}))

(defn mouse-click! [browser {:keys [x y] :as point}]
  (let [ev {:x           x
            :y           y
            :click-count 1
            :button      DispatchMouseEventButton/LEFT}]
    (mouse-move! browser point)
    (dispatch-mouse-event! browser (assoc ev :type DispatchMouseEventType/MOUSE_PRESSED))
    (dispatch-mouse-event! browser (assoc ev :type DispatchMouseEventType/MOUSE_RELEASED))))


