(ns cuic.core
  (:refer-clojure :exclude [eval])
  (:require [clojure.string :as string]
            [clojure.set :refer [difference]]
            [cuic.impl.browser :as browser]
            [cuic.impl.dom-node :refer [node-id->node node->node-id document?]]
            [cuic.impl.exception :as ex]
            [cuic.impl.retry :as retry]
            [cuic.impl.html :as html]
            [cuic.impl.js-bridge :as js]
            [cuic.impl.input :as input]
            [cuic.impl.util :as util]
            [cuic.util :refer [run mutation one at-most-one one-visible]])
  (:import (com.github.kklisura.cdt.services ChromeDevToolsService)
           (java.io File)
           (com.github.kklisura.cdt.protocol.commands DOM)))

;; Global options

(defonce ^{:dynamic true
           :doc     "Current browser instance that is used to perform queries
                     and mutation. This **must** be set with `clojure.core/binding`
                     before anything can be done."} *browser*
  nil)

(defonce ^{:dynamic true
           :doc     "Timeout in millisecond after `wait` stops retrying
                     and throws a `cuic.WaitTimeoutException`"} *timeout*
  10000)

(defonce ^{:dynamic true
           :doc     "Defines the simulated keyboard typing speed. Can be one of the
                     predefined defaults `#{:slow :normal :fast :tycitys}` or custom
                     integer indicating strokes per second (e.g. `50` = 50
                     strokes/sec)"} *typing-speed*
  :normal)

(defonce ^{:dynamic true
           :doc     "Experimental feature flags. Use with caution!"} *experimental-features*
  {:click-check false})

;; General

(defn browser
  "Returns the current browser instance."
  ([] (or *browser* (throw (IllegalStateException. "No browser set! Did you forget to call `use-browser`?")))))

(defn dev-tools
  "Escape hatch for underlying Chrome dev tools service"
  [] ^ChromeDevToolsService (some-> *browser* (browser/tools)))

(defn sleep
  "Holds the execution the given milliseconds"
  [ms]
  {:pre [(pos? ms)]}
  (Thread/sleep ms))

(defmacro wait
  "Evaluates the given expression and returns the value if it is truthy,
   otherwise pauses execution for a moment and re-tries to evaluate the
   expression. Continues this until thruthy value or timeout exception
   occurs."
  [expr]
  `(retry/loop* #(do ~expr) '~expr *timeout*))

;; Queries

(defn- -DOM
  ([] ^DOM (.getDOM (dev-tools)))
  ([node] ^DOM (.getDOM (browser/tools (:browser node)))))

(defn document
  "Returns the root document node or nil if document is not available"
  []
  (run (some-> (.getDocument (-DOM))
               (.getNodeId)
               (node-id->node :document (browser)))))

(defn q
  "Performs a CSS query to the subtree of the given root node and returns a
   vector of matched nodes. If called without the root node, page document node
   is used as a root node for the query."
  ([parent ^String selector]
   {:pre [(string? selector)]}
   (when-let [n (at-most-one parent)]
     (run (->> (.querySelectorAll (-DOM n) (node->node-id n) selector)
               (map #(node-id->node % :element (:browser n)))
               (seq)))))
  ([^String selector]
   (q (document) selector)))

(defn eval
  "Evaluate JavaScript expression in the global JS context. Return value of the
   expression is converted into Clojure data structure. Supports async
   expressions (await keyword) and promises."
  [^String js-code]
  (run (js/eval (browser) js-code)))

(defn eval-in [node ^String js-code]
  "Evaluates JavaScript expression in the given node context so that JS `this`
   points to the given node. Return value of the expression is converted into
   Clojure data structure. Supports async expressions (await keyword)."
  (run (js/eval-in (one node) js-code)))


(defn visible?
  "Returns boolean whether the given DOM node is visible in DOM or not"
  [node]
  (run (js/eval-in (at-most-one node) "!!this.offsetParent")))

(defn rect
  "Returns a bounding client rect for the given node"
  [node]
  (run (util/bounding-box (at-most-one node))))

(defn value
  "Returns the current value of the given input element."
  [input-node]
  (run (js/eval-in (at-most-one input-node) "this.value")))

(defn options
  "Returns a list of options `{:keys [value text selected]}` for the given HTML
   select element."
  [select-node]
  (run (js/eval-in (at-most-one select-node) "Array.prototype.slice.call(this.options).map(function(o){return{value:o.value,text:o.text,selected:o.selected};})")))

(defn outer-html
  "Returns the outer html of the given node in clojure.data.xml format
  (node is a map of {:keys [tag attrs content]})"
  [node]
  (when-let [n (at-most-one node)]
    (-> (run (.getOuterHTML (-DOM n) (node->node-id n) nil nil))
        (html/parse (document? n)))))

(defn text-content
  "Returns the raw text content of the given DOM node."
  [node]
  (when-let [n (at-most-one node)]
    (if (document? n)
      (text-content (first (q n "body")))
      (run (js/eval-in n "this.textContent")))))

(defn inner-text
  "Returns the inner text of the given DOM node"
  [node]
  (when-let [n (at-most-one node)]
    (if (document? n)
      (inner-text (first (q n "body")))
      (js/eval-in n "this.innerText"))))

(defn attrs
  "Returns a map of attributes and their values for the given DOM node"
  [node]
  (when-let [n (at-most-one node)]
    (run (->> (.getAttributes (-DOM n) (node->node-id n))
              (partition 2 2)
              (map (fn [[k v]] [(keyword k) v]))
              (into {})))))

(defn classes
  "Returns a set of CSS classes for the given DOM node"
  [node]
  (when-let [attributes (attrs node)]
    (as-> attributes $
          (get $ :class "")
          (string/split $ #"\s+")
          (map string/trim $)
          (remove string/blank? $)
          (set $))))

(defn term-freqs
  "Returns a number of occurrences per term in the given node's inner text"
  [node]
  (when-let [text (inner-text node)]
    (->> (string/split text #"[\n\s\t]+")
         (map string/trim)
         (remove string/blank?)
         (group-by identity)
         (map (fn [[t v]] [t (count v)]))
         (into {}))))

(defn has-class?
  "Returns boolean whether the given DOM node has the given class or not"
  [node ^String class]
  (some-> (classes node) (contains? class)))

(defn matches?
  "Returns boolean whether the given node matches the given CSS selector or not."
  [node ^String selector]
  (run (js/exec-in (at-most-one node) "try {return this.matches(sel);}catch(e){return false;}" ["sel" selector])))

(defn active?
  "Returns boolean whether the given node is active (focused) or not"
  [node]
  (run (js/eval-in (at-most-one node) "document.activeElement === this")))

(defn checked?
  "Returns boolean whether the given radio button / checkbox is checked or not"
  [node]
  (run (js/eval-in (at-most-one node) "!!this.checked")))

(defn disabled?
  "Returns boolean whether the given input / select / button is disabled or not"
  [node]
  (run (js/eval-in (at-most-one node) "!!this.disabled")))

(defn running-tasks
  "Returns a list of currently running browser tasks. Currently only in-flight
   HTTP requests are supported."
  []
  (browser/tasks (browser)))

(defn screenshot
  "Takes a screen capture from the given DOM node and returns a BufferedImage
   instance containing the screenshot. DOM node must be visible or otherwise
   an exception is thrown.

   If no DOM node is given, takes screenshot from the entire page."
  ([node]
    ; for some reason, Chrome's "clip" option in .captureScreenshot does not match the
    ; bounding box rect of the node so we need to do it manually here in JVM...
   (run (let [n (one-visible node)]
          (util/scroll-into-view! n)
          (let [{:keys [left top width height]} (util/bounding-box n)]
            (-> (util/scaled-screenshot (:browser n))
                (util/crop left top width height)))))))


;; Mutations

(defn goto!
  "Navigates the page to the given URL."
  [url]
  (mutation (.navigate (.getPage (dev-tools)) url)))

(defn scroll-to!
  "Scrolls window to the given DOM node if that node is not already visible
   in the current viewport"
  [node]
  (mutation (util/scroll-into-view! (one-visible node))))

(defn click!
  "Clicks the given DOM element."
  [node]
  (mutation
    (let [n  (one-visible node)
          bb (rect n)]
      (util/clear-clicks! n)
      (if-not (util/bbox-visible? bb)
        (throw (ex/retryable-ex "Node is visible but has zero width and/or height")))
      (util/scroll-into-view! n)
      (input/mouse-click! (:browser n) (util/bbox-center bb))
      (if (:click-check *experimental-features*)
        (util/was-really-clicked? n)
        true))))

(defn hover!
  "Hover mouse over the given DOM element."
  [node]
  (mutation
    (let [n  (one-visible node)
          bb (rect n)]
      (if-not (util/bbox-visible? bb)
        (throw (ex/retryable-ex "Node is visible but has zero width and/or height")))
      (util/scroll-into-view! n)
      (input/mouse-move! (:browser n) (util/bbox-center bb)))
    true))

(defn focus!
  "Focus on the given DOM element."
  [node]
  (mutation
    (let [n (one-visible node)]
      (util/scroll-into-view! n)
      (.focus (-DOM n) (node->node-id n) nil nil))
    true))

(defn select-text!
  "Selects all text from the given input DOM element. If element is
   not active, it is activated first by clicking it."
  [input-node]
  (mutation
    (let [n (one-visible input-node)]
      (if-not (active? n) (click! n))
      (js/exec-in n "try{var len = this.value.length; this.setSelectionRange(0,len); return len;}catch(e){}"))
    true))

(defn type!
  "Types text to the given input element. If element is not active,
   it is activated first by clicking it."
  [input-node & keys]
  (mutation
    (let [n (one-visible input-node)]
      (if-not (active? n) (click! n))
      (input/type! (:browser n) (vec keys) *typing-speed*)
      (value n))))

(defn clear-text!
  "Clears all text from the given input DOM element by selecting all
   text and pressing backspace. If element is not active, it is
   activated first by clicking it."
  [input-node]
  (select-text! input-node)
  (type! input-node :backspace))

(defn select!
  "Selects the given value (or values if multiselect) to the given select node"
  [select-node & values]
  {:pre [(every? string? values)]}
  (mutation
    (js/exec-in (one-visible select-node) "
       if (this.nodeName.toLowerCase() !== 'select') throw new Error('Not a select node');
       var opts = Array.prototype.slice.call(this.options);
       for(var i = 0; i < opts.length; i++) {
         var o = opts[i];
         if ((o.selected = vals.includes(o.value)) && !this.multiple) {
           break;
         }
       }
       this.dispatchEvent(new Event('input', {bubbles: true}));
       this.dispatchEvent(new Event('change', {bubbles: true}));
     " ["vals" (vec values)])
    (count values)))

(defn upload!
  "Sets the file(s) to the given input node. All files must instances of
   class `java.io.File`."
  [input-node & files]
  {:pre [(seq files)
         (every? #(instance? File %) files)]}
  (mutation
    (let [node  (one-visible input-node)
          paths (->> (map (fn [f#] (.getAbsolutePath f#)) files)
                     (apply list))]
      (.setFileInputFiles (-DOM node) paths (node->node-id node) nil nil)
      (count paths))))
