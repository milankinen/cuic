(ns cuic.core
  (:refer-clojure :exclude [eval])
  (:require [clojure.string :as string]
            [clojure.set :refer [difference]]
            [cuic.impl.macro :refer [let-some let-existing let-visible ignore-stale]]
            [cuic.impl.browser :as browser]
            [cuic.impl.dom-node :refer [->DOMNode maybe existing node-id node-id->object-id]]
            [cuic.impl.exception :refer [call call-node] :as ex]
            [cuic.impl.retry :as retry]
            [cuic.impl.html :as html]
            [cuic.impl.js-bridge :as js]
            [cuic.impl.input :as input]
            [cuic.impl.util :as util])
  (:import (com.github.kklisura.cdt.services ChromeDevToolsService)
           (java.io Closeable File)))

;; Options

(defonce ^:dynamic *browser* nil)
(defonce ^:dynamic *config*
  {:typing-speed               :normal
   :timeout                    10000
   :take-screenshot-on-timeout true
   :screenshot-dir             "target/__screenshots__"
   :snapshot-dir               "test/__snapshots__"
   :abort-on-failed-assertion  false})

;; General

(defmacro run-query
  {:style/indent 0}
  [[binding expr] & body]
  `(let-some [~binding ~expr]
     (ignore-stale ~@body)))

(defmacro run-mutation
  {:style/indent 0}
  [mutation & [description]]
  `(try
     (retry/loop* #(do ~mutation true) *browser* *config*)
     nil
     (catch Exception ex#
       (throw (ex/mutation-failure ex# '~(or description mutation))))))

(defn current-browser
  "Returns the current browser instance."
  ([] (or *browser* (throw (IllegalStateException. "No browser set! Did you forget to call `use-browser`?")))))

(defn dev-tools
  "Escape hatch for underlying Chrome dev tools service"
  [] ^ChromeDevToolsService (browser/tools (current-browser)))

(defn sleep
  "Holds the execution the given milliseconds"
  [ms]
  {:pre [(pos? ms)]}
  (Thread/sleep ms))

(defn launch!
  "TODO docs"
  ([opts]
   (browser/launch! opts))
  ([] (launch! {:headless true})))

(defn close!
  "Closes the given resource"
  [^Closeable resource]
  (.close resource))

(defmacro wait
  "Evaluates the given expression and returns the value if it is truthy,
   otherwise pauses execution for a moment and re-tries to evaluate the
   expression. Continues this until thruthy value or timeout exception
   occurs."
  [expr]
  `(retry/loop* #(do ~expr) *browser* *config*))

;; Queries

(defn document
  "Returns the root document node or nil if document is not available"
  []
  (as-> (call-node #(-> (.getDOM (dev-tools)) (.getDocument))) $
        (node-id->object-id (current-browser) (.getNodeId $))
        (->DOMNode $ (current-browser))
        (with-meta $ {::document true})))

(defn q
  "Performs a CSS query to the subtree of the given root node and returns a
   vector of matched nodes. If called without the root node, page document node
   is used as a root node for the query."
  ([root-node ^String selector]
   (or (run-query [n root-node]
         (->> (call-node #(-> (.getDOM (dev-tools))
                              (.querySelectorAll (node-id n) selector)))
              (map (partial node-id->object-id (:browser n)))
              (mapv #(->DOMNode % (:browser n)))))
       []))
  ([^String selector]
   (q (document) selector)))

(defn eval
  "Evaluate JavaScript expression in the global JS context. Return value of the
   expression is converted into Clojure data structure. Supports async
   expressions (await keyword)."
  [^String js-code]
  (js/eval (current-browser) js-code))

(defn eval-in [node-ctx ^String js-code]
  "Evaluates JavaScript expression in the given node context so that JS 'this'
   points to the given node. Return value of the expression is converted into
   Clojure data structure. Supports async expressions (await keyword)."
  (let-existing [n node-ctx]
                (js/eval-in n js-code)))

(defn visible?
  "Returns boolean whether the given DOM node is visible in DOM or not"
  [node]
  (or (run-query [n node]
        (js/eval-in n "!!this.offsetParent"))
      false))

(defn rect
  "Returns a bounding client rect for the given node"
  [node]
  (run-query [n node]
    (util/bounding-box n)))

(defn value
  "Returns the current value of the given input element."
  [input-node]
  (run-query [n input-node]
    (js/eval-in n "this.value")))

(defn options
  "Returns a list of options {:keys [value text selected]} for the given HTML
   select element."
  [select-node]
  (or (run-query [n select-node]
        (js/eval-in n "Array.prototype.slice.call(this.options).map(function(o){return{value:o.value,text:o.text,selected:o.selected};})"))
      []))

(defn outer-html
  "Returns the outer html of the given node in clojure.data.xml format
  (node is a map of {:keys [tag attrs content]})"
  [node]
  (run-query [n node]
    (->> (call-node #(.getOuterHTML (.getDOM (dev-tools)) (node-id n) nil nil))
         (html/parse (true? (::document (meta n)))))))

(defn text-content
  "Returns the raw text content of the given DOM node."
  [node]
  (run-query [n node]
    (if (true? (::document (meta n)))
      (text-content (first (q n "body")))
      (js/eval-in n "this.textContent"))))

(defn inner-text
  "Returns the inner text of the given DOM node"
  [node]
  (run-query [n node]
    (if (true? (::document (meta n)))
      (inner-text (first (q n "body")))
      (js/eval-in n "this.innerText"))))

(defn attrs
  "Returns a map of attributes and their values for the given DOM node"
  [node]
  (or (run-query [n node]
        (->> (call-node #(.getAttributes (.getDOM (dev-tools)) (node-id n)))
             (partition 2 2)
             (map (fn [[k v]] [(keyword k) v]))
             (into {})))
      {}))

(defn classes
  "Returns a set of CSS classes for the given DOM node"
  [node]
  (as-> (attrs node) $
        (get $ :class "")
        (string/split $ #"\s+")
        (map string/trim $)
        (remove string/blank? $)
        (set $)))

(defn term-freqs
  "Returns a number of occurrences per term in the given nodes inner text"
  [node]
  (->> (string/split (or (inner-text node) "") #"[\n\s\t]+")
       (map string/trim)
       (remove string/blank?)
       (group-by identity)
       (map (fn [[t v]] [t (count v)]))
       (into {})))

(defn has-class?
  "Returns boolean whether the given DOM node has the given class or not"
  [node ^String class]
  (contains? (classes node) class))

(defn matches?
  "Returns boolean whether the given node matches the given CSS selector or not."
  [node ^String selector]
  (or (run-query [n node]
        (js/exec-in n "try {return this.matches(sel);}catch(e){return false;}" ["sel" selector]))
      false))

(defn active?
  "Returns boolean whether the given node is active (focused) or not"
  [node]
  (or (run-query [n node]
        (js/eval-in n "document.activeElement === this"))
      false))

(defn checked?
  "Returns boolean whether the given radio button / checkbox is checked or not"
  [node]
  (or (run-query [n node]
        (js/eval-in n "!!this.checked"))
      false))

(defn disabled?
  "Returns boolean whether the given input / select / button is disabled or not"
  [node]
  (or (run-query [n node]
        (js/eval-in n "!!this.disabled"))
      false))

(defn page-screenshot
  "Takes a screen capture from the currently visible page and returns a
   BufferedImage instance containing the screenshot."
  []
  (util/scaled-screenshot (current-browser)))

(defn screenshot
  "Takes a screen capture from the given DOM node and returns a BufferedImage
   instance containing the screenshot. DOM node must be visible or otherwise
   an exception is thrown."
  [node]
  (let-visible [n node]
    (util/scroll-into-view! n)
    ; for some reason, Chrome's "clip" option in .captureScreenshot does not match the
    ; bounding box rect of the node so we need to do it manually here in JVM...
    (let [bb (util/bounding-box n)]
      (-> (util/scaled-screenshot (current-browser))
          (util/crop (:left bb) (:top bb) (:width bb) (:height bb))))))


;; Mutations

(defmacro goto!
  "Navigates the page to the given URL."
  [url]
  `(run-mutation
     (call #(.navigate (.getPage (dev-tools)) ~url))
     '(goto! ~url)))

(defmacro scroll-to!
  "Scrolls window to the given DOM node if that node is not already visible
   in the current viewport"
  [node]
  `(run-mutation
     (let-visible [node# ~node]
       (util/scroll-into-view! node#))
     '(scroll-to! ~node)))

(defmacro click!
  "Clicks the given DOM element."
  [node]
  `(run-mutation
     (let-visible [node# ~node]
       (let [r# (rect node#)]
         (if (and (pos? (:width r#))
                  (pos? (:height r#)))
           (do (util/scroll-into-view! node#)
               (input/mouse-click! (:browser node#) (util/bbox-center node#))
               ; this tries to mitigate the nasty edge case when the node is "moving" in
               ; the DOM due to animations/data loading etc... this simulates human behaviour
               ; "oh I missed, let's try again
               (let [was-clicked# (util/was-really-clicked? node#)]
                 (util/clear-clicks! (:browser node#))
                 (if-not was-clicked#
                   (throw (ex/retryable "Node could not be clicked for some reason")))))
           (throw (ex/retryable "Node is visible but has zero width and/or height")))))
     (~'click! ~node)))

(defmacro hover!
  "Hover mouse over the given DOM element."
  [node]
  `(run-mutation
     (let-visible [node# ~node]
       (let [r# (rect node#)]
         (if (and (pos? (:width r#))
                  (pos? (:height r#)))
           (do (util/scroll-into-view! node#)
               (input/mouse-move! (:browser node#) (util/bbox-center node#)))
           (throw (ex/retryable "Node is visible but has zero width and/or height")))))
     '(hover! ~node)))

(defmacro focus!
  "Focus on the given DOM element."
  [node]
  `(run-mutation
     (let-visible [node# ~node]
       (util/scroll-into-view! node#)
       (call-node #(-> (.getDOM (browser/tools (:browser node#)))
                       (.focus (node-id node#) nil nil))))
     '(focus! ~node)))

(defmacro select-text!
  "Selects all text from the given input DOM element. If element is
   not active, it is activated first by clicking it."
  [input-node]
  `(run-mutation
     (let-visible [node# ~input-node]
       (if-not (active? node#) (click! node#))
       (js/exec-in node# "try{this.setSelectionRange(0,this.value.length);}catch(e){}"))
     '(select-text! ~input-node)))

(defmacro type!
  "Types text to the given input element. If element is not active,
   it is activated first by clicking it."
  [input-node & keys]
  `(run-mutation
     (let-visible [node# ~input-node]
       (if-not (active? node#) (click! node#))
       (input/type! (:browser node#) ~(vec keys) (:typing-speed *config*)))
     ~(cons 'type! (cons input-node keys))))

(defmacro clear-text!
  "Clears all text from the given input DOM element by selecting all
   text and pressing backspace. If element is not active, it is
   activated first by clicking it."
  [input-node]
  `(run-mutation
     (do (select-text! ~input-node)
         (type! ~input-node :backspace))
     '(clear-text! ~input-node)))

(defmacro select!
  "Selects the given value (values if multiselect) to the given select node"
  [select-node & values]
  `(run-mutation
     (let-visible [node# ~select-node]
       (let [vals# ~(vec values)]
         (assert (every? string? vals#))
         (js/exec-in node# "
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
          " ["vals" (vec vals#)])))
     ~(cons 'select! (cons select-node values))))

(defmacro set-files!
  "Sets the file(s) to the given input node. All files must instances of
   class java.io.File."
  [input-node & files]
  `(run-mutation
     (let-visible [node# ~input-node]
       (let [fs# ~files]
         (assert (every? #(instance? File %) fs#))
         (call-node #(-> (.getDOM (browser/tools (:browser node#)))
                         (.setFileInputFiles (->> (map (fn [f#] (.getAbsolutePath f#)) fs#)
                                                  (apply list)))))))))