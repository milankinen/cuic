(ns cuic.core
  (:refer-clojure :exclude [eval])
  (:require [clojure.string :as string]
            [clojure.set :refer [difference]]
            [clj-chrome-devtools.commands.page :as page]
            [clj-chrome-devtools.commands.dom :as dom]
            [cuic.impl.ws-invocation :refer [call]]
            [cuic.impl.exception :refer [with-stale-ignored]]
            [cuic.impl.session :as session]
            [cuic.impl.browser :as browser :refer [c]]
            [cuic.impl.task-tracking :as tracking]
            [cuic.impl.dom-node :refer [->DOMNode maybe existing visible]]
            [cuic.impl.js-bridge :as js]
            [cuic.impl.util :as util]
            [cuic.impl.html :as html]
            [cuic.impl.input :as input]
            [cuic.impl.retry :as retry])
  (:import (java.io Closeable)))

(declare default-mutation-wrapper)

(defonce ^:dynamic *browser* nil)
(defonce ^:dynamic *config*
  {:typing-speed     :normal
   :timeout          10000
   :mutation-wrapper #(default-mutation-wrapper %)})

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

(defn sleep
  "Holds the execution the given milliseconds"
  [ms]
  {:pre [(pos? ms)]}
  (Thread/sleep ms))

(defn default-session
  "Creates a new Chrome/Chromium session using default user's profile and settings.
   If user has already browser sessions open, this session shares the contents of those
   sessions."
  ([^String executable]
   (session/default executable))
  ([] (default-session nil)))

(defn new-session!
  "Creates a new unique Chrome/Chromium session that does not share anything with
   other open sessions / browsers. This is the recommended way of creating new
   sessions for automated tests"
  ([window-size ^String executable]
   {:pre [(or (nil? window-size)
              (and (integer? (:width window-size))
                   (integer? (:height window-size))))]}
   (session/create! executable window-size))
  ([window-size] (new-session! window-size nil))
  ([] (new-session! nil)))

(defn open-browser!
  "Opens a new browser window to the given session"
  ([session {:keys [^Long rdport
                    ^Boolean headless
                    ^Boolean gpu]
             :or   {rdport   nil
                    headless true
                    gpu      false}
             :as   opts}]
   (browser/open! session opts))
  ([session] (open-browser! session {})))

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

(defn background-tasks
  "Returns a vector of the active background tasks."
  [] (-> (browser/tracking (-browser))
         (tracking/get-active-tasks)))

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

(defn document
  "Returns the root document node or nil if document is not available"
  []
  (-> (call dom/get-document (c (-browser)) {:depth 1})
      (get-in [:root :node-id])
      (->DOMNode (-browser))
      (with-meta {::document true})))

(defn q
  "Performs a CSS query to the subtree of the given root node and returns a
   vector of matched nodes. If called without the root node, page document node
   is used as a root node for the query."
  ([root-node ^String selector]
   (or (-run-node-query [n root-node]
         (->> (call dom/query-selector-all (c (:browser n)) {:node-id (:id n) :selector selector})
              (:node-ids)
              (mapv #(->DOMNode % (:browser n)))))
       []))
  ([^String selector]
   (q (document) selector)))

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
    (->> (call dom/get-outer-html (c (:browser n)) {:node-id (:id n)})
         (:outer-html)
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
        (->> (call dom/get-attributes (c (:browser n)) {:node-id (:id n)})
             (:attributes)
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

(defn goto!
  "Navigates the page to the given URL."
  [^String url]
  (-run-mutation
    (call page/navigate (c (-browser)) {:url url})))

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
    (call dom/focus (c (:browser n)) {:node-id (:id n)})))

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
  (letfn [(watched-http-request? [{:keys [task-type request-type]}]
            (and (= :http-request task-type)
                 (contains? #{:document :xhr} request-type)))
          (tasks []
            (->> (background-tasks)
                 (filter watched-http-request?)
                 (set)))]
    (let [before (tasks)]
      (operation)
      ; JavScript apps are slow - let them some time to fire the requests
      ; before we actually start polling them
      (sleep 100)
      (wait (empty? (difference before (tasks)))))))
