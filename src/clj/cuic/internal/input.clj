(ns ^:no-doc cuic.internal.input
  (:require [clojure.string :as string]
            [cuic.internal.cdt :refer [invoke]]
            [cuic.internal.keycodes :refer [keycode-mapping]]
            [cuic.internal.util :refer [cuic-ex quoted]]))

(set! *warn-on-reflection* true)

(defn- invalid-modifier-ex [given]
  (cuic-ex (str "Invalid modifier " (quoted given) ". Valid values are:"
                "\n * Alt"
                "\n * Cmd"
                "\n * Command"
                "\n * Ctrl"
                "\n * Meta"
                "\n * Shift")))

(defn- invalid-keycode-ex [given]
  (cuic-ex (str "Invalid keycode " (quoted given) ". Valid values are:"
                "\n * " (string/join "\n * " (sort (keys keycode-mapping))))))

(defn- get-key-mapping [code]
  (or (get keycode-mapping code)
      (throw (invalid-keycode-ex code))))

(defn mouse-move [cdt {:keys [x y]}]
  (invoke {:cdt  cdt
           :cmd  "Input.dispatchMouseEvent"
           :args {:type "mouseMoved"
                  :x    (double x)
                  :y    (double y)}}))

(defn mouse-click [cdt {:keys [x y button clicks]}]
  {:pre [(keyword? button)]}
  (doseq [type ["mousePressed" "mouseReleased"]]
    (invoke {:cdt  cdt
             :cmd  "Input.dispatchMouseEvent"
             :args {:type       type
                    :x          (double x)
                    :y          (double y)
                    :button     (name button)
                    :clickCount clicks}})))

(defn key-event [cdt type modifiers+key]
  (let [xs (string/split (name modifiers+key) #"\+")
        code (peek xs)
        mapping (get-key-mapping code)
        modifiers (->> (pop xs)
                       (map #(case %
                               "Alt" 1
                               "Ctrl" 2
                               ("Meta" "Cmd" "Command") 3
                               "Shift" 4
                               (throw (invalid-modifier-ex %))))
                       (reduce #(bit-or %1 (bit-shift-left 1 (dec %2))) 0))
        which (:keyCode mapping)
        args (cond-> (assoc mapping
                       :type type
                       :modifiers modifiers)
                     (some? which)
                     (assoc :windowsVirtualKeyCode which
                            :nativeVirtualKeyCode which))]
    (invoke {:cdt  cdt
             :cmd  "Input.dispatchKeyEvent"
             :args args})))

(defn press-key [cdt key]
  (key-event cdt "keyDown" key)
  (Thread/sleep 10)
  (key-event cdt "keyUp" key))

(defn type-char [cdt ch]
  (case ch
    \newline (press-key cdt 'Enter)
    \tab (press-key cdt 'Tab)
    (invoke {:cdt  cdt
             :cmd  "Input.dispatchKeyEvent"
             :args {:type "char" :text (str ch)}})))

(defn type-text [cdt text chars-per-minute]
  (when (seq text)
    (loop [[ch & xs] text]
      (type-char cdt ch)
      (when xs
        (when (pos-int? chars-per-minute)
          (Thread/sleep (/ 60000 chars-per-minute)))
        (recur xs)))))
