(ns cuic.core
  (:refer-clojure :exclude [eval])
  (:require [clojure.string :as string]
            [clojure.set :refer [difference]]
            [cuic.impl.browser :as browser :refer [tools]]
            [cuic.impl.dom-node :refer [->DOMNode maybe existing visible node-id node-id->object-id]]
            [cuic.impl.exception :refer [call call-node with-stale-ignored]]
            [cuic.impl.retry :as retry]
            [cuic.impl.html :as html]
            [cuic.impl.js-bridge :as js]
            [cuic.impl.input :as input]
            [cuic.impl.util :as util])
  (:import (java.io Closeable)
           (com.github.kklisura.cdt.services ChromeDevToolsService)
           (java.awt.image BufferedImage)))

(declare default-mutation-wrapper)

(defonce ^:dynamic *browser* nil)
(defonce ^:dynamic *config*
  {:typing-speed     :normal
   :timeout          10000
   :mutation-wrapper #(default-mutation-wrapper %)
   :snapshot-dir     "test/__snapshots__"
   ; TODO figure out correct values for these?
   :image-match      {:hash-bits 512 :threshold 4}})

(defmacro -run-mutation
  "Runs the given code block inside mutation wrapper.
   ** Used internally, do not touch! **"
  {:style/indent 0}
  [& body]
  `(do (apply (:mutation-wrapper *config*) [#(do ~@body)])
       nil))

(defmacro -run-node-mutation
  "Shortcut for mutation of the given visible node.
  ** Used internally, do not touch! **"
  {:style/indent 0}
  [[node-binding node-expr] & body]
  `(let [~node-binding (visible ~node-expr)]
     (-run-mutation ~@body)))

(defmacro -run-node-query
  "Shortcut for data query of the given visible node.
  ** Used internally, do not touch! **"
  {:style/indent 0}
  [[node-binding node-expr] expr]
  `(with-stale-ignored
     (if-let [~node-binding (maybe ~node-expr)]
       ~expr)))

(defn -browser
  "Returns the current browser instance.
   ** Used internally, do not touch! **"
  ([] (or *browser* (throw (IllegalStateException. "No browser set! Did you forget to call `use-browser`?")))))

(defn- -t [] ^ChromeDevToolsService (tools (-browser)))

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
  `(retry/loop* #(do ~expr) (:timeout *config*) '~expr))

(defmacro with-retry
  "Evaluates each (mutation) statement in retry-loop so that if the
   mutation throws any cuic related error, the errored statement is
   retried until the expression passes or timeout exception occurs."
  [& statements]
  `(do ~@(map (fn [s] `(retry/loop* #(do ~s true) (:timeout *config*) '~s)) statements)
       nil))

(defn running-activities
  "Returns a vector of the currently running activities."
  ([] (browser/activities (-browser))))


(defn document
  "Returns the root document node or nil if document is not available"
  []
  (as-> (call-node #(-> (.getDOM (-t)) (.getDocument))) $
        (node-id->object-id (-browser) (.getNodeId $))
        (->DOMNode $ (-browser))
        (with-meta $ {::document true})))

(defn q
  "Performs a CSS query to the subtree of the given root node and returns a
   vector of matched nodes. If called without the root node, page document node
   is used as a root node for the query."
  ([root-node ^String selector]
   (or (-run-node-query [n root-node]
         (->> (call-node #(-> (.getDOM (-t))
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
  (js/eval (-browser) js-code))

(defn eval-in [node-ctx ^String js-code]
  "Evaluates JavaScript expression in the given node context so that JS 'this'
   points to the given node. Return value of the expression is converted into
   Clojure data structure. Supports async expressions (await keyword)."
  (js/eval-in (existing node-ctx) js-code))

(defn value
  "Returns the current value of the given input element."
  [input-node]
  (-run-node-query [n input-node]
    (js/eval-in n "this.value")))

(defn selected-options
  "Returns a list of selected options {:keys [value text]} for the given HTML
   select element."
  [select-node]
  (or (-run-node-query [n select-node]
        (js/eval-in n "Array.prototype.slice.call(this.selectedOptions).map(function(o){return{value:o.value,text:o.text};})"))
      []))

(defn outer-html
  "Returns the outer html of the given node in clojure.data.xml format
  (node is a map of {:keys [tag attrs content]})"
  [node]
  (-run-node-query [n node]
    (->> (call-node #(.getOuterHTML (.getDOM (-t)) (node-id n) nil nil))
         (html/parse (true? (::document (meta n)))))))

(defn text-content
  "Returns the raw text content of the given DOM node."
  [node]
  (-run-node-query [n node]
    (if (true? (::document (meta n)))
      (text-content (first (q n "body")))
      (js/eval-in n "this.textContent"))))

(defn attrs
  "Returns a map of attributes and their values for the given DOM node"
  [node]
  (or (-run-node-query [n node]
        (->> (call-node #(.getAttributes (.getDOM (-t)) (node-id n)))
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

(defn visible?
  "Returns boolean whether the given DOM node is visible in DOM or not"
  [node]
  (or (-run-node-query [n node]
        (js/eval-in n "!!this.offsetParent"))
      false))

(defn has-class?
  "Returns boolean whether the given DOM node has the given class or not"
  [node ^String class]
  (contains? (classes node) class))

(defn matches?
  "Returns boolean whether the given node matches the given CSS selector or not."
  [node ^String selector]
  (or (-run-node-query [n node]
        (js/exec-in n "try {return this.matches(sel);}catch(e){return false;}" ["sel" selector]))
      false))

(defn active?
  "Returns boolean whether the given node is active (focused) or not"
  [node]
  (or (-run-node-query [n node]
        (js/eval-in n "document.activeElement === this"))
      false))

(defn checked?
  "Returns boolean whether the given radio button / checkbox is checked or not"
  [node]
  (or (-run-node-query [n node]
        (js/eval-in n "!!this.checked"))))

(defn page-screenshot
  "Takes a screen capture from the currently visible page and returns a
   BufferedImage instance containing the screenshot."
  ([{:keys [masked-nodes]}]
   (-> (util/scaled-screenshot (-browser))
       (util/mask-nodes masked-nodes)))
  ([] (page-screenshot {})))

(defn screenshot
  "Takes a screen capture from the given DOM node and returns a BufferedImage
   instance containing the screenshot. DOM node must be visible or otherwise
   an exception is thrown."
  ([node {:keys [masked-nodes]}]
    ; for some reason, Chrome's "clip" option in .captureScreenshot does not match the
    ; bounding box rect of the node so we need to do it manually here in JVM...
   (let [n  (visible node)
         _  (util/scroll-into-view! n)
         bb (util/bounding-box n)]
     (-> (util/scaled-screenshot (-browser))
         (util/mask-nodes masked-nodes)
         (util/crop (:left bb) (:top bb) (:width bb) (:height bb)))))
  ([node] (screenshot node {})))

(defn png-bytes
  "Converts the given (screenshot) image to PNG format and returns a byte array
   of the encoded data."
  [^BufferedImage image]
  (util/png-bytes image))

(defn goto!
  "Navigates the page to the given URL."
  [^String url]
  (-run-mutation
    (call #(.navigate (.getPage (-t)) url))))

(defn scroll-to!
  "Scrolls window to the given DOM node if that node is not already visible
   in the current viewport"
  [node]
  (-run-node-mutation [n node]
    (util/scroll-into-view! n)))

(defn click!
  "Clicks the given DOM element."
  [node]
  (-run-node-mutation [n node]
    (util/scroll-into-view! n)
    (input/mouse-click! (:browser n) (util/bbox-center n))))

(defn hover!
  "Hover mouse over the given DOM element."
  [node]
  (-run-node-mutation [n node]
    (util/scroll-into-view! n)
    (input/mouse-move! (:browser n) (util/bbox-center n))))

(defn focus!
  "Focus on the given DOM element."
  [node]
  (-run-node-mutation [n node]
    (util/scroll-into-view! n)
    (call-node #(-> (.getDOM (tools (:browser n)))
                    (.focus (node-id node) nil nil)))))

(defn select-text!
  "Selects all text from the given input DOM element. If element is
   not active, it is activated first by clicking it."
  [input-node]
  (-run-node-mutation [n input-node]
    (if-not (active? n) (click! n))
    (js/exec-in n "try{this.setSelectionRange(0,this.value.length);}catch(e){}")))

(defn type!
  "Types text to the given input element. If element is not active,
   it is activated first by clicking it."
  [input-node & keys]
  (-run-node-mutation [n input-node]
    (if-not (active? n) (click! n))
    (input/type! (:browser n) keys (:typing-speed *config*))))

(defn clear-text!
  "Clears all text from the given input DOM element by selecting all
   text and pressing backspace. If element is not active, it is
   activated first by clicking it."
  [input-node]
  (doto input-node
    (select-text!)
    (type! :backspace)))

(defn default-mutation-wrapper
  "Default wrapper that surrounds for each mutation function.
   Holds the execution until all document (re)loads, XHR requests
   and animations have been finished that were started after
   the mutation."
  [operation]
  (letfn [(watched-task? [{:keys [task-type request-type]}]
            (and (= :http-request task-type)
                 (contains? #{:document :xhr} request-type)))
          (watched-tasks []
            (->> (running-activities)
                 (filter watched-task?)
                 (set)))]
    (let [before (watched-tasks)]
      (operation)
      ; JavScript apps are slow - let them some time to fire the requests before we actually start polling them
      (sleep 100)
      (wait (empty? (difference (watched-tasks) before))))))
