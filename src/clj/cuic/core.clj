(ns cuic.core
  "Core functions for ui queries and interactions"
  (:refer-clojure :exclude [find type])
  (:require [clojure.string :as string]
            [cuic.chrome :refer [chrome? devtools page]]
            [cuic.internal.cdt :refer [invoke]]
            [cuic.internal.node :refer [wrap-node
                                        node?
                                        maybe-node?
                                        stale-as-nil
                                        stale-as-ex
                                        get-node-id
                                        get-node-name
                                        get-node-cdt
                                        rename]]
            [cuic.internal.page :refer [navigate-to
                                        navigate-forward
                                        navigate-back]]
            [cuic.internal.runtime :refer [get-window
                                           window?
                                           exec-js-code
                                           scroll-into-view-if-needed]]
            [cuic.internal.input :refer [mouse-move
                                         mouse-click
                                         type-kb]]
            [cuic.internal.html :refer [parse-document parse-element boolean-attributes]]
            [cuic.internal.util :refer [rewrite-exceptions
                                        cuic-ex
                                        timeout-ex
                                        check-arg
                                        quoted
                                        decode-base64
                                        url-str?]])
  (:import (java.io File)
           (cuic TimeoutException)))

(set! *warn-on-reflection* true)

;;;
;;; config
;;;

(defn- -chars-per-minute [speed]
  (check-arg [#(or (contains? #{:slow :normal :fast :very-fast :tycitys} %)
                   (pos-int? %))
              "positive integer or one of #{:slow :normal :fast :tycitys}"]
             [speed "typing speed"])
  (case speed
    :slow 300
    :normal 600
    :fast 1200
    :very-fast 2400
    :tycitys 12000
    speed))

(def ^:dynamic *browser*
  "Default browser that will be used for core queries such as
   [[cuic.core/find]], [[cuic.core/document]] or [[cuic.core/window]]
   if not explicitly defined during the query invocation time.

   Use `binding` to assign default for e.g. single test run:

   ```clojure
   (defn shared-chrome-fixture [t]
     (binding [*browser* (get-shared-chrome-instance-somehow)]
       (t)))
   ```"
  nil)

(def ^:dynamic *timeout*
  "Default implicit timeout (in milliseconds) for queries and interactions
   before `cuic.TimeoutException` is thrown

   Use `binding` to assign default for e.g. single test run:

   ```clojure
   (defn browser-test-defaults-fixture [t]
     (binding [*timeout*       20000
               *typing-speed* :tycitys]
       (t)))
   ```"
  5000)

(def ^:dynamic *typing-speed*
  "Default typing speed in **typed characters per minute**. Supports
   also the following preset values:
   * `:slow` - 300 chars/min
   * `:normal` - 600 chars/min
   * `:fast` - 1200 chars/min
   * `:very-fast` - 2400 chars/min
   * `:tycitys` - 12000 chars/min

   Use `binding` to assign default for e.g. single test run:

   ```clojure
   (defn browser-test-defaults-fixture [t]
     (binding [*timeout*       20000
               *typing-speed* :tycitys]
       (t)))
   ```"
  :normal)

(def ^:dynamic *query-scope*
  "DOM node that acts as a root node for [[cuic.core/find]] and
   [[cuic.core/query]] queries, unless othewise specified at query
   invocation time. Setting this value to `nil` indicates that
   queries should use document scope by default.

   Do not bind this variable directly, instead use [[cuic.core/in]]
   to define query scope in your application code."
  nil)

(defn set-browser!
  "Globally resets the default browser. Useful for example REPL
   usage. See [[cuic.core/*browser*]] for more details."
  [browser]
  (rewrite-exceptions
    (check-arg [chrome? "chrome instance"] [browser "value"])
    (alter-var-root #'*browser* (constantly browser))))

(defn set-timeout!
  "Globally resets the default implicit timeout. Useful for
   example REPL usage. See [[cuic.core/*timeout*]] for more details."
  [timeout]
  (rewrite-exceptions
    (check-arg [nat-int? "positive integer or zero"] [timeout "timeout"])
    (alter-var-root #'*timeout* (constantly timeout))))

(defn set-typing-speed!
  "Globally resets the default typing speed. Useful for example
   REPL usage. See [[cuic.core/*typing-speed*]] for more details."
  [speed]
  (rewrite-exceptions
    (-chars-per-minute speed)
    (alter-var-root #'*typing-speed* (constantly speed))))

;;;
;;; misc
;;;

(defn sleep
  "Stops the current thread execution for the given
   n milliseconds."
  [ms]
  {:pre [(nat-int? ms)]}
  (Thread/sleep ms))

(defmacro wait
  "Macro that waits until the given expression returns a
   truthy value or timeouts. Uses [[cuic.core/*timeout*]] by
   default but the timeout value can be overrided by providing
   it as a second parameter.

   **Attention:** the waited expression may be invoked
   multiple times so it should **not** mutate anything during
   the invocation!"
  ([expr] `(wait ~expr cuic.core/*timeout*))
  ([expr timeout]
   `(let [timeout# ~timeout
          start-t# (System/currentTimeMillis)]
      (check-arg [nat-int? "positive integer or zero"] [timeout# "timeout"])
      (loop []
        (or ~expr
            (if (>= (- (System/currentTimeMillis) start-t#) timeout#)
              (throw (timeout-ex "Timeout exceeded while waiting for truthy"
                                 "value from expression:" ~(pr-str expr)))
              (recur)))))))

(defmacro in
  "Macro that runs its body using the given node as \"root scope\"
   for queries: all matching elements must be found under the node.
   Scope must be a valid DOM node or otherwise an exception will
   be thrown.

   ```clojure
   ;; the following codes are equivalent

   (c/in (c/find \"#header\")
     (let [items (c/query \".navi-item\")]
       ...))

   (let [header (c/find \"#header\")
         items (c/query {:from header :by \".navi-item\"})]
     ...)
   ```"
  [scope & body]
  `(rewrite-exceptions
     (let [root-node# ~scope]
       (check-arg [node? "dom node"] [root-node# "scope root"])
       (binding [*query-scope* root-node#]
         ~@body))))

(defn as
  "Assigns a new debug name for the give node. The assigned name
   will be displayed in REPL and in error messages but it does
   not have any effect on other behaviour.

   ```clojure
   (-> (c/find \"#sidebar\")
       (c/as \"Sidebar\")
   ```

   Note that usually one should prefer `(find {:as <name> :by <sel>})`
   for assigning names instead of `(-> (find <sel>) (as <name>)` because
   then the name will be displayed in error message if `find` fails
   for some reason. See [[cuic.core/find]] and [[cuic.core/query]] for
   more details."
  [node name]
  (rewrite-exceptions
    (check-arg [string? "string"] [name "node name"])
    (check-arg [node? "dom node"] [node "renamed node"])
    (rename node name)))

;;;
;;; core queries
;;;

(defn- -require-default-browser []
  (or *browser* (throw (cuic-ex "Default browser not set"))))

(defn window
  "Returns a window object for the current default browser.
   Note that window can only be used as `this` in [[cuic.core/eval-js]]
   and [[cuic.core/exec-js]] but not as query scope. For query
   scopes, use [[cuic.core/document]] instead.

   Default browser can be overriden by providing browser
   explicitly as a parameter."
  ([] (rewrite-exceptions (window (-require-default-browser))))
  ([browser]
   (rewrite-exceptions
     (check-arg [chrome? "chrome instance"] [browser "given browser"])
     (get-window (devtools browser)))))

(defn document
  "Returns a document object (DOM node) for the current default
   browser. Default browser can be overriden by providing browser
   explicitly as a parameter."
  ([] (rewrite-exceptions (document (-require-default-browser))))
  ([browser]
   (rewrite-exceptions
     (check-arg [chrome? "chrome instance"] [browser "given browser"])
     (let [cdt (devtools browser)
           res (invoke {:cdt  cdt
                        :cmd  "DOM.getDocument"
                        :args {:depth 0}})
           doc (wrap-node cdt (:root res) nil "document" nil)]
       (when (nil? doc)
         (throw (cuic-ex "Cound not get page document")))
       (vary-meta doc assoc ::document? true)))))

(defn active-element
  "Returns active element (DOM node) for the current default
   browser or `nil` if there are no active elements in the
   page at the moment. Default browser can be overriden by
   providing browser explicitly as a parameter."
  ([] (rewrite-exceptions (active-element (-require-default-browser))))
  ([browser]
   (rewrite-exceptions
     (stale-as-ex (cuic-ex "Couldn't get active element")
       (check-arg [chrome? "chrome instance"] [browser "given browser"])
       (let [cdt (devtools browser)
             res (invoke {:cdt  cdt
                          :cmd  "Runtime.evaluate"
                          :args {:expression "document.activeElement"}})
             obj (:result res)]
         (when obj
           (when-let [node (-> (invoke {:cdt  cdt
                                        :cmd  "DOM.describeNode"
                                        :args (select-keys obj [:objectId])})
                               (:node))]
             (wrap-node cdt node nil (:description obj) nil))))))))

(defn find
  "Tries to find **exactly one** element by the given css selector
   and throws an exception element wasn't found **or** there are
   multiple elements matching the selector.

   If any element is not found from the DOM, `find` tries to wait
   for it until the element appears to the DOM or timeout exceeds.
   By default, the wait timeout is [[cuic.core/*timeout*]] but it
   can be overriden per invocation.

   Basic usage:
   ```clojure
   (def search-input (c/find \".navi input.search\")
   ```

   In addition to plain css selector, `find` can be invoked with
   more information by using map form:
   ```clojure
   (find {:by      <selector>  ; mandatory - CSS selector for the query
          :from    <scope>     ; optional - root scope for query, defaults to document see cuic.core/in
          :as      <name>      ; optional - name of the result node, see cuic.core/as
          :timeout <ms>        ; optional - wait timeout in ms, defaults to cuic.core/*timeout*
          })

   ;; examples

   (c/find {:by \".navi input.search\"
            :as \"Quick search input\"})

   (c/find {:from    (find \"#sidebar\")
            :by      \".logo\"
            :timeout 10000})
   ```"
  [selector]
  (rewrite-exceptions
    (check-arg [#(or (string? %) (map? %)) "string or map"] [selector "selector"])
    (loop [selector selector]
      (if (map? selector)
        (let [{:keys [from by as timeout]
               :or   {from    (or *query-scope* (document))
                      timeout *timeout*}} selector
              _ (check-arg [node? "node"] [from "from scope"])
              _ (check-arg [string? "string"] [by "selector"])
              _ (check-arg [#(or (string? %) (nil? %)) "string"] [as "alias"])
              _ (check-arg [nat-int? "positive integer or zero"] [timeout "timeout"])
              cdt (devtools (-require-default-browser))
              ctx-id (or (stale-as-nil (get-node-id from))
                         (throw (cuic-ex "Could not find node" (quoted (or as by)) "because"
                                         "context node" (quoted (get-node-name from)) "does not"
                                         "exist anymore")))
              start-t (System/currentTimeMillis)]
          (loop []
            (let [result (invoke {:cdt  cdt
                                  :cmd  "DOM.querySelectorAll"
                                  :args {:nodeId ctx-id :selector by}})
                  node-ids (:nodeIds result)
                  n-nodes (count node-ids)
                  elapsed (- (System/currentTimeMillis) start-t)]
              (case n-nodes
                0 (if (< elapsed timeout)
                    (do (sleep (min 100 (- *timeout* elapsed)))
                        (recur))
                    (throw (cuic-ex "Could not find node" (quoted as) "from"
                                    (quoted (get-node-name from)) "with selector"
                                    (quoted by) "in" timeout "milliseconds")))
                1 (or (stale-as-nil (wrap-node cdt {:nodeId (first node-ids)} from as by))
                      (recur))
                (throw (cuic-ex "Found too many" (str "(" n-nodes ")") (quoted as)
                                "nodes from" (quoted (get-node-name from))
                                "with selector" (quoted by)))))))
        (recur {:by selector})))))

(defn query
  "Works like [[cuic.core/find]] but returns 0..n nodes matching
   the given css selector. In case of no results, `nil` will be
   returned, otherwise the return value is a vector of DOM nodes.

   Basic usage:
   ```clojure
   (def navi-items
     (c/query \".navi .item\")
   ```

   In addition to plain css selector, `query` can be invoked with
   more information by using map form:
   ```clojure
   (query {:by      <selector>  ; mandatory - CSS selector for the query
           :from    <scope>     ; optional - root scope for query, defaults to document see cuic.core/in
           :as      <name>      ; optional - name for the result nodes
           })

   ;; examples

   (c/query {:by \".navi .item\"
             :as \"Navigation item\"})

   (c/find {:from (find \"#sidebar\")
            :by   \".item\"
            :as   \"Sidebar item\"})
   ```

   Note that unlike [[cuic.core/find]], [[cuic.core/query]] **does not
   wait** for the results: if there are zero nodes matching the given
   selector at the query time, the result set will be `nil`. If some
   results are expected, use [[cuic.core/query]] in conjunction with
   [[cuic.core/wait]] e.g.

   ```clojure
   (c/wait (c/query \".navi .item\"))
   ```"
  [selector]
  (rewrite-exceptions
    (check-arg [#(or (string? %) (map? %)) "string or map"] [selector "selector"])
    (loop [selector selector]
      (if (map? selector)
        (let [{:keys [from by as]
               :or   {from (or *query-scope* (document))}} selector
              _ (check-arg [node? "node"] [from "from scope"])
              _ (check-arg [string? "string"] [by "selector"])
              _ (check-arg [#(or (string? %) (nil? %)) "string"] [as "alias"])
              cdt (devtools (-require-default-browser))
              ctx-id (or (stale-as-nil (get-node-id from))
                         (throw (cuic-ex "Could not query" (quoted as) "nodes with selector"
                                         (quoted by) "because context node" (quoted (get-node-name from))
                                         "does not exist anymore")))]
          (->> (invoke {:cdt  cdt
                        :cmd  "DOM.querySelectorAll"
                        :args {:nodeId ctx-id :selector by}})
               (:nodeIds)
               (keep #(stale-as-nil (wrap-node cdt {:nodeId %} from as by)))
               (vec)
               (doall)
               (not-empty)))
        (recur {:by selector})))))

;;;
;;; js code execution
;;;

(defn- -exec-js [code args this]
  (let [result (exec-js-code {:code code
                              :args args
                              :this this})]
    (if-let [error (:error result)]
      (throw (cuic-ex (str "JavaScript error occurred:\n\n" error)))
      (:return result))))

(defn eval-js
  "Evaluates the given JavaScript expression in the given `this` object
   context and returns the expression's result value. Expression may
   take arguments from Clojure code.

   By default `this` refers to the [[cuic.core/window]] of the current
   default browser but it may be rebound to any node or window object.

   Arguments serializable JSON values:
    * `nil` (will be converted to `null`
    * booleans
    * numbers
    * maps (will be converted to objects)
    * collections (will be converted to arrays)

   Likewise the return value of the expression is limited to JSON
   values:
    * `null`, `undefined` (will be converted to `nil`)
    * booleans
    * numbers
    * object (will be converted to maps)
    * arrays (will be converted to vectors)

   ```clojure
   ;; Return title of the current browser window
   (c/eval-js \"document.title\")

   ;; Return async value
   (c/eval-js \"await new Promise(resolve => setTimeout(() => resolve('tsers'), 500))\")

   ;; Increment list values in JS side
   (c/eval-js \"xs.map(x => x + 1)\" {:xs [1 2 3]})

   ;; Perform some arithmetics in JS side
   (c/eval-js \"a + b.num\" {:a 1000 :b {:num 337}})

   ;; Get placeholder value from the query input
   (let [q (c/find \"#query\")]
     (c/eval-js \"this.placeholder\" {} q))
   ```

   See also [[cuic.core/exec-js]] if you want to execute statements
   in JavaScript side."
  ([expr]
   (rewrite-exceptions (eval-js expr {} (window))))
  ([expr args]
   (rewrite-exceptions (eval-js expr args (window))))
  ([expr args this]
   (rewrite-exceptions
     (check-arg [string? "string"] [expr "expression"])
     (check-arg [map? "map"] [args "call arguments"])
     (check-arg [#(or (node? %) (window? %)) "node or window"] [this "this binding"])
     (stale-as-ex (cuic-ex "Can't evaluate JavaScript expression on" (quoted this)
                           "because it does not exist anymore")
       (-exec-js (str "return " expr ";") args this)))))

(defn exec-js
  "Executes the given JavaScript function body in the given `this`
   object context. The executed body may also be parametrized by
   arguments from Clojure code, and it can return value by using
   JavaScript's `return` statement in the end. Function body may
   `await` async values/effects.

   By default `this` refers to the [[cuic.core/window]] of the current
   default browser but it may be rebound to any node or window object.

   Arguments serializable JSON values:
    * `nil` (will be converted to `null`
    * booleans
    * numbers
    * maps (will be converted to objects)
    * collections (will be converted to arrays)

   Likewise the return value of the expression is limited to JSON
   values:
    * `null`, `undefined` (will be converted to `nil`)
    * booleans
    * numbers
    * object (will be converted to maps)
    * arrays (will be converted to vectors)

   ```clojure
   ;; Reset document title
   (c/exec-js \"document.title = 'tsers'\")

   ;; Reset input's placeholder value
   (let [input (c/find \"#my-input\")]
     (c/exec-js \"this.setAttribute('placeholder', text)\" {:text \"tsers\"} input))

   ;; Reset document's title and return the prior value
   (c/exec-js \"const prev = document.title;
                document.title = 'tsers';
                return prev;\")
   ```

   See also [[cuic.core/eval-js]] if you only want to query data
   from JavaScript side without performing any side-effects.
   "
  ([body]
   (rewrite-exceptions (exec-js body {} (window))))
  ([body args]
   (rewrite-exceptions (exec-js body args (window))))
  ([body args this]
   (rewrite-exceptions
     (check-arg [string? "string"] [body "function body"])
     (check-arg [map? "map"] [args "call arguments"])
     (check-arg [#(or (node? %) (window? %)) "node or window"] [this "this binding"])
     (stale-as-ex (cuic-ex "Can't execute JavaScript code on" (quoted this)
                           "because it does not exist anymore")
       (-exec-js body args this)))))

;;;
;;; properties
;;;

(defn- -js-prop
  ([node js-expr] (-js-prop node js-expr {}))
  ([node js-expr args]
   (let [code (str "return " (string/replace js-expr #"\n\s+" " ") ";")]
     (-exec-js code args node))))

(defn- -bb [node]
  (-exec-js "let r = this.getBoundingClientRect();
             return {
               top: r.top,
               left: r.left,
               width: r.width,
               height: r.height
             };" {} node))

(defn client-rect
  "Returns the bounding client rect for the given node as a map of:
   ```clojure
   {:top    <number>
    :left   <number>
    :width  <number>
    :height <number>}
   ```

   See https://developer.mozilla.org/en-US/docs/Web/API/Element/getBoundingClientRect
   for more info."
  [node]
  (rewrite-exceptions
    (check-arg [maybe-node? "dom node"] [node "node"])
    (stale-as-ex (cuic-ex "Can't get client rect from node" (quoted (get-node-name node))
                          "because node does not exist anymore")
      (-bb node))))

(defn visible?
  "Returns boolean whether the given node is visible in DOM or not"
  [node]
  (rewrite-exceptions
    (check-arg [maybe-node? "dom node"] [node "node"])
    (stale-as-ex (cuic-ex "Can't check visibility of node" (quoted (get-node-name node))
                          "because node does not exist anymore")
      (-js-prop node "!!this.offsetParent"))))

(defn inner-text
  "Returns the inner text of the given node. See
   https://developer.mozilla.org/en-US/docs/Web/API/HTMLElement/innerText
   for more info."
  [node]
  (rewrite-exceptions
    (check-arg [maybe-node? "dom node"] [node "node"])
    (stale-as-ex (cuic-ex "Can't get inner text from node" (quoted (get-node-name node))
                          "because node does not exist anymore")
      (-js-prop node "this.innerText"))))

(defn text-content
  "Returns the text content of the given node. See
   https://developer.mozilla.org/en-US/docs/Web/API/Node/textContent
   for more info."
  [node]
  (rewrite-exceptions
    (check-arg [maybe-node? "dom node"] [node "node"])
    (stale-as-ex (cuic-ex "Can't get text content from node" (quoted (get-node-name node))
                          "because node does not exist anymore")
      (-js-prop node "this.textContent"))))

(defn outer-html
  "Returns the outer HTML of the given node as [hiccup](https://github.com/weavejester/hiccup).
   Comments will be returned using `:-#comment` tag and CDATA nodes
   will be returned using `:-#data` tag."
  [node]
  (rewrite-exceptions
    (check-arg [maybe-node? "dom node"] [node "node"])
    (stale-as-ex (cuic-ex "Can't get outer html from node" (quoted (get-node-name node))
                          "because node does not exist anymore")
      (let [cdt (get-node-cdt node)
            node-id (get-node-id node)
            html (-> (invoke {:cdt  cdt
                              :cmd  "DOM.getOuterHTML"
                              :args {:nodeId node-id}})
                     (:outerHTML))]
        (if (::document? (meta node))
          (parse-document html)
          (parse-element html))))))

(defn value
  "Returns the current string value of the given input/select/textarea
   element. Throws an exception if node is not a valid input element"
  [node]
  (rewrite-exceptions
    (check-arg [maybe-node? "dom node"] [node "node"])
    (stale-as-ex (cuic-ex "Can't get value from node" (quoted (get-node-name node))
                          "because node does not exist anymore")
      (when-not (-js-prop node "'value' in this")
        (throw (cuic-ex "Node" (quoted (get-node-name node)) "is not a valid input element")))
      (-js-prop node "this.value"))))

(defn options
  "Returns a list of options `{:keys [value text selected]}` for the
   given node. Throws an exception if node is not a HTML select element."
  [node]
  (rewrite-exceptions
    (check-arg [maybe-node? "dom node"] [node "node"])
    (stale-as-ex (cuic-ex "Can't get options from node" (quoted (get-node-name node))
                          "because node does not exist anymore")
      (when (not= "SELECT" (-js-prop node "this.tagName"))
        (throw (cuic-ex "Node" (quoted (get-node-name node)) "is not a valid select element")))
      (-js-prop node "Array.prototype.slice
                      .call(this.options || [])
                      .map(({value, text, selected}) => ({ value, text, selected }))"))))

(defn attributes
  "Returns node's HTML attributes as a map of keyword keys and
   string values. Boolean attribute values will be converted to
   `true` if they are present

   ```clojure
   ;; html element <div id='foo' data-val='1' class='foo bar'></div>
   (c/attributes (c/find \"#foo\"))
   ; =>
   {:id       \"foo\"
    :data-val \"1\"
    :class    \"foo bar\"}
   ```"
  [node]
  (rewrite-exceptions
    (check-arg [maybe-node? "dom node"] [node "node"])
    (stale-as-ex (cuic-ex "Can't get attributes from node" (quoted (get-node-name node))
                          "because node does not exist anymore")
      (let [cdt (get-node-cdt node)]
        (->> (invoke {:cdt  cdt
                      :cmd  "DOM.getAttributes"
                      :args {:nodeId (get-node-id node)}})
             (:attributes)
             (partition-all 2 2)
             (map (fn [[k v]] [(keyword k) (if (contains? boolean-attributes k) true v)]))
             (into {}))))))

(defn classes
  "Returns a set of css classes (as strings) for the given node. Returns
   an empty set if node does not have any classes.

   ```clojure
   ;; <div id='foo' class='foo bar'></div>
   (c/classes (c/find \"#foo\"))
   ; =>
   #{\"foo\" \"bar\"}

   ;; <div id='foo'></div>
   (c/classes (c/find \"#foo\"))
   ; =>
   #{}
   ```"
  [node]
  (rewrite-exceptions
    (check-arg [maybe-node? "dom node"] [node "node"])
    (as-> (attributes node) $
          (get $ :class "")
          (string/split $ #"\s+")
          (map string/trim $)
          (remove string/blank? $)
          (set $))))

(defn has-class?
  "Returns boolean whether the given node has the tested
   css class or not"
  [node class]
  (rewrite-exceptions
    (check-arg [maybe-node? "dom node"] [node "node"])
    (check-arg [string? "string"] [class "tested class name"])
    (contains? (classes node) class)))

(defn matches?
  "Returns boolean whether the given node matches the tested css
   selector or not. Throws an exception if selector is not valid."
  [node selector]
  (rewrite-exceptions
    (check-arg [maybe-node? "dom node"] [node "node"])
    (check-arg [string? "string"] [selector "tested css selector"])
    (stale-as-ex (cuic-ex "Can't match css selector to node" (quoted (get-node-name node))
                          "because node does not exist anumore")
      (let [match (-js-prop node "(function(n){try{return n.matches(s)}catch(_){}})(this)" {:s selector})]
        (when (nil? match)
          (throw (cuic-ex "The tested css selector" (quoted selector) "is not valid")))
        match))))

(defn has-focus?
  "Returns boolean whether the given node has focus or not"
  [node]
  (rewrite-exceptions
    (check-arg [maybe-node? "dom node"] [node "node"])
    (stale-as-ex (cuic-ex "Can't resolve focus from node" (quoted (get-node-name node))
                          "because node does not exist anymore")
      (-js-prop node "document.activeElement === this"))))

(defn checked?
  "Returns boolean whether the given node is checked or not. Throws
   an exception if the node is not a valid input element."
  [node]
  (rewrite-exceptions
    (check-arg [maybe-node? "dom node"] [node "node"])
    (stale-as-ex (cuic-ex "Can't resolve checked status from node" (quoted (get-node-name node))
                          "because node does not exist anymore")
      (when-not (-js-prop node "'checked' in this")
        (throw (cuic-ex "Node" (quoted (get-node-name node)) "is not a valid input element")))
      (-js-prop node "!!this.checked"))))

(defn disabled?
  "Returns boolean whether the given node is disabled or not."
  [node]
  (rewrite-exceptions
    (check-arg [maybe-node? "dom node"] [node "node"])
    (stale-as-ex (cuic-ex "Can't resolve disabled status from node" (quoted (get-node-name node))
                          "because node does not exist anymore")
      (-js-prop node "!!this.disabled"))))

;;;
;;; actions
;;;

(defn- -wait-visible [node]
  (try
    (wait (-js-prop node "!!this.offsetParent"))
    (catch TimeoutException _
      false)))

(defn- -wait-enabled [node]
  (try
    (wait (-js-prop node "!this.disabled"))
    (catch TimeoutException _
      false)))

(defn goto
  "Navigates browser to the given url, equivalent to user typing
   url to the browser address bar and pressing enter. Url **must**
   contain the protocol. Reloads the page, even if navigating to
   the current page, and waits until the page is loaded or timeout
   exceeds. Uses [[cuic.core/*timeout*]] by default but it can be
   overrided by giving the timeout as an option to the invodation.

   Uses the current browser by default but it can be overrided
   by providing browser as an option to the invocation.

   ```clojure
   ;; Use default browser and timeout
   (c/goto \"https://clojuredocs.org\")

   ;; Use non-default browser
   (c/goto \"https://clojuredocs.org\" {:browser another-chrome})

   ;; Use non-default timeout
   (c/goto \"https://clojuredocs.org\" {:timeout 300000})
   ```"
  ([url] (goto url {}))
  ([url opts]
   (rewrite-exceptions
     (let [{:keys [timeout browser]
            :or   {browser (-require-default-browser)
                   timeout *timeout*}} opts]
       (check-arg [url-str? "valid url string"] [url "url"])
       (check-arg [nat-int? "positive integer or zero"] [timeout "timeout"])
       (navigate-to (page browser) url timeout))
     nil)))

(defn go-back
  "Simulates browser back button click. Returns boolean whether the
   back button was pressed or not. Back button pressing is disabled
   if there is no browser history to go back anymore.

   ```clojure
   ;; Use default browser and timeout
   (c/go-back)

   ;; Use non-default browser
   (c/go-back {:browser another-chrome})

   ;; Use non-default timeout
   (c/go-back {:timeout 1000})
   ```"
  ([] (go-back {}))
  ([opts]
   (rewrite-exceptions
     (let [{:keys [timeout browser]
            :or   {browser (-require-default-browser)
                   timeout *timeout*}} opts]
       (check-arg [nat-int? "positive integer or zero"] [timeout "timeout"])
       (boolean (navigate-back (page browser) timeout))))))

(defn go-forward
  "Simulates browser forward button click. Returns boolean whether the
   forward button was pressed or not. Forward button pressing is disabled
   if browser is already at the latest navigated page.

   ```clojure
   ;; Use default browser and timeout
   (c/go-forward)

   ;; Use non-default browser
   (c/go-forward {:browser another-chrome})

   ;; Use non-default timeout
   (c/go-forward {:timeout 1000})
   ```"
  ([] (go-forward {}))
  ([opts]
   (rewrite-exceptions
     (let [{:keys [timeout browser]
            :or   {browser (-require-default-browser)
                   timeout *timeout*}} opts]
       (check-arg [nat-int? "positive integer or zero"] [timeout "timeout"])
       (boolean (navigate-forward (page browser) timeout))))))

(defn type
  "Simulates keyboard typing. Typed text can be either string or a symbol
   representing keycode of the pressed key.

   In case of text, characters are pressed and released one by one using
   the provided typing speed. Typing speed uses [[cuic.core/*typing-speed*]]
   by default but it can be overrided by giving the speed as an option
   to the invocation.

   In case of symbol, the symbol must be a valid keycode with optional
   modifiers separated by '+' (see examples below). You can use e.g.
   https://keycode.info to get desired key code (`event.code`) for the
   typed key. The complete list of available key codes can be found from
   https://developer.mozilla.org/en-US/docs/Web/API/KeyboardEvent/keyCode

   Note that this function is pretty low level and it **does not** focus
   the typed text/key to any node. See [[cuic.core/fill]] for a more
   high-level alternative.

   Uses the current browser by default but it can be overrided
   by providing browser as an option to the invocation.

   ```clojure
   ;; Type some text
   (c/type \"Tsers!\")

   ;; Type some text very fast
   (c/type \"Tsers!\" {:speed :tycitys})

   ;; Type some text to non-default browser
   (c/type \"Tsers!\" {:browser another-chrome})

   ;; Focus on next tab index element
   (c/type 'Tab)

   ;; Focus on previous tab index element
   (c/type 'Shift+Tab)
   ```"
  ([text] (type text {}))
  ([text opts]
   (rewrite-exceptions
     (check-arg [#(or (string? %) (simple-symbol? %)) "string or keycode symbol"] [text "typed input"])
     (let [{:keys [browser speed]
            :or   {browser (-require-default-browser)
                   speed   *typing-speed*}} opts
           cdt (devtools browser)]
       (type-kb cdt (if (symbol? text) [text] text) (-chars-per-minute speed))
       nil))))

(defn scroll-into-view
  "Scrolls the given node into view if it's not in the view already.
   Waits [[cuic.core/*timeout*]] until the node becomes visible or
   throws an exception if timeout exceeds.

   Returns the target node for threading."
  [node]
  (rewrite-exceptions
    (check-arg [node? "dom node"] [node "target node"])
    (stale-as-ex (cuic-ex "Can't scroll node" (quoted (get-node-name node))
                          "into view because node does not exist anymore")
      (when-not (-wait-visible node)
        (throw (cuic-ex "Can't scroll node" (quoted (get-node-name node))
                        "into view because node is not visible")))
      (scroll-into-view-if-needed node)
      node)))

(defn hover
  "First scrolls the given node into view (if needed) and then moves the
   mouse over the node. Waits [[cuic.core/*timeout*]] until the node
   becomes visible or throws an exception if timeout exceeds.

   Throws an exception if node is not hoverable: it has either zero
   width or zero height.

   Returns the target node for threading."
  [node]
  (rewrite-exceptions
    (check-arg [node? "dom node"] [node "hover target"])
    (stale-as-ex (cuic-ex "Can't hover over node" (quoted (get-node-name node))
                          "because node does not exist anymore")
      (when-not (-wait-visible node)
        (throw (cuic-ex "Can't hover over node" (quoted (get-node-name node))
                        "because node is not visible")))
      (scroll-into-view-if-needed node)
      (let [{:keys [top left width height]} (-bb node)
            cdt (get-node-cdt node)
            x (+ left (/ width 2))
            y (+ top (/ height 2))]
        (when (or (zero? width)
                  (zero? height))
          (throw (cuic-ex "Node" (quoted (get-node-name node))
                          "is not hoverable")))
        (mouse-move cdt {:x x :y y})
        node))))

(defn click
  "First scrolls the given node into view (if needed) and then clicks
   the node once. Waits [[cuic.core/*timeout*]] until the node becomes
   visible or throws an exception if timeout exceeds.

   Throws an exception if node is not hoverable: it has either zero
   width or zero height or it is disabled.

   Returns the clicked node for threading.

   ```clojure
   (c/click (c/find \"button#save\"))
   ```"
  [node]
  (rewrite-exceptions
    (check-arg [node? "dom node"] [node "clicked node"])
    (stale-as-ex (cuic-ex "Can't click node" (quoted (get-node-name node))
                          "because node does not exist anymore")
      (when-not (-wait-visible node)
        (throw (cuic-ex "Can't click node" (quoted (get-node-name node))
                        "because node is not visible")))
      (scroll-into-view-if-needed node)
      (when-not (-wait-enabled node)
        (throw (cuic-ex "Can't click node" (quoted (get-node-name node))
                        "because node is disabled")))
      (let [{:keys [top left width height]} (-bb node)
            cdt (get-node-cdt node)
            x (+ left (/ width 2))
            y (+ top (/ height 2))]
        (when (or (zero? width)
                  (zero? height))
          (throw (cuic-ex "Node" (quoted (get-node-name node))
                          "is not clickable")))
        (mouse-move cdt {:x x :y y})
        (mouse-click cdt {:x x :y y :button :left})
        node))))

(defn focus
  "First scrolls the given node into view (if needed) and then focuses
   on the node. Waits [[cuic.core/*timeout*]] until the node becomes
   visible or throws an exception if timeout exceeds.

   Returns the focused node for threading."
  [node]
  (rewrite-exceptions
    (check-arg [node? "dom node"] [node "focused node"])
    (stale-as-ex (cuic-ex "Can't focus on node" (quoted (get-node-name node))
                          "because node does not exist anymore")
      (when-not (-wait-visible node)
        (throw (cuic-ex "Can't focus on node" (quoted (get-node-name node))
                        "because node is not visible")))
      (scroll-into-view-if-needed node)
      (when-not (-wait-enabled node)
        (throw (cuic-ex "Can't focus on node" (quoted (get-node-name node))
                        "because node is disabled")))
      (let [cdt (get-node-cdt node)]
        (invoke {:cdt  cdt
                 :cmd  "DOM.focus"
                 :args {:nodeId (get-node-id node)}})
        node))))

(defn select-text
  "First scrolls the given node into view (if needed) and then selects
   all text from the node. Node must be an input/textarea element or
   otherwise an exception is thrown. Waits [[cuic.core/*timeout*]] until
   the node becomes visible or throws an exception if timeout exceeds.

   Returns the target node for threading."
  [node]
  (rewrite-exceptions
    (check-arg [node? "dom node"] [node "selected node"])
    (stale-as-ex (cuic-ex "Can't select text from node" (quoted (get-node-name node))
                          "because node does not exist anymore")
      (when-not (-js-prop node "typeof this.select === 'function'")
        (throw (cuic-ex "Node" (quoted (get-node-name node)) "is not a valid input element")))
      (when-not (-wait-visible node)
        (throw (cuic-ex "Can't select text from node" (quoted (get-node-name node))
                        "because node is not visible")))
      (scroll-into-view-if-needed node)
      (when-not (-wait-enabled node)
        (throw (cuic-ex "Can't select text from node" (quoted (get-node-name node))
                        "because node is disabled")))
      (-exec-js "this.select()" {} node)
      node)))

(defn clear-text
  "First scrolls the given node into view (if needed) and then clears
   all text from the node. Node must be an input/textarea element or
   otherwise an exception is thrown. Waits [[cuic.core/*timeout*]] until
   the node becomes visible or throws an exception if timeout exceeds.

   Returns the target node for threading."
  [node]
  (rewrite-exceptions
    (check-arg [node? "dom node"] [node "cleared node"])
    (stale-as-ex (cuic-ex "Can't clear text from node" (quoted (get-node-name node))
                          "because node does not exist anymore")
      (when-not (-js-prop node "typeof this.select === 'function'")
        (throw (cuic-ex "Node" (quoted (get-node-name node)) "is not a valid input element")))
      (when-not (-wait-visible node)
        (throw (cuic-ex "Can't clear text from node" (quoted (get-node-name node))
                        "because node is not visible")))
      (scroll-into-view-if-needed node)
      (when-not (-wait-enabled node)
        (throw (cuic-ex "Can't clear text from node" (quoted (get-node-name node))
                        "because node is disabled")))
      (-exec-js "this.select()" {} node)
      (type-kb (get-node-cdt node) ['Backspace] 0)
      node)))

(defn fill
  "Fills the given text to the given `input` or `textarea` element,
   clearing any previous text before typing the new text. If node is not
   in the view, scrolls the node into view first. Waits [[cuic.core/*timeout*]]
   until the node becomes fillable (visible and not disabled) or
   throws an exception if the timeout exceeds.

   Uses [[cuic.core/*typing-speed*]] by default but it can be overrided
   by giving the speed as a third parameter. See [[cuic.core/*typing-speed*]]
   for valid values. Only text is allowed: if you need to type keycodes,
   use [[cuic.core/type]] instead. The exceptions are newline character
   `\\n` and tab `\\t` that will be interpreted as their respective keycodes.

   Returns the filled node for threading.

   ```clojure
   (def comment (c/find \"input.comment\"))

   ;; Fill some text to comment and commit by pressing enter
   (c/fill comment \"Tsers\")
   (c/type 'Enter)

   ;; Same with terser form
   (c/fill comment \"Tsers\\n\")

   ;; Fill text fast
   (c/fill comment \"Tsers\" {:speed :tycitys})
   ```"
  ([node text] (fill node text *typing-speed*))
  ([node text opts]
   (rewrite-exceptions
     (let [{:keys [speed] :or {speed *typing-speed*}} opts
           cdt (get-node-cdt node)
           chars-per-min (-chars-per-minute speed)]
       (check-arg [node? "dom node"] [node "filled node"])
       (check-arg [string? "string"] [text "typed text"])
       (stale-as-ex (cuic-ex "Can't fill node" (quoted (get-node-name node))
                             "because node does not exist anymore")
         (when-not (-wait-visible node)
           (throw (cuic-ex "Can't fill node" (quoted (get-node-name node))
                           "because node is not visible")))
         (scroll-into-view-if-needed node)
         (when-not (-wait-enabled node)
           (throw (cuic-ex "Can't fill node" (quoted (get-node-name node))
                           "because node is disabled")))
         (-exec-js "this.select()" {} node)
         (type-kb cdt ['Backspace] chars-per-min)
         (type-kb cdt text chars-per-min)
         node)))))

(defn choose
  "Chooses the given options (strings) from the given node. Node must
   be a html select element or otherwise an exception will be thrown.
   If node is not in the view, scrolls the node into view first. Waits
   [[cuic.core/*timeout*]] until the node becomes fillable (visible
   and not disabled) or throws an exception if the timeout exceeds.

   Returns the target node for threading."
  [node & options]
  (rewrite-exceptions
    (check-arg [node? "dom node"] [node "updated node"])
    (check-arg [#(every? string? %) "strings"] [options "selected options"])
    (stale-as-ex (cuic-ex "Can't choose options from node" (quoted (get-node-name node))
                          "because node does not exist anymore")
      (when (not= "SELECT" (-js-prop node "this.tagName"))
        (throw (cuic-ex "Node" (quoted (get-node-name node)) "is not a valid select element")))
      (when-not (-wait-visible node)
        (throw (cuic-ex "Can't choose options from node" (quoted (get-node-name node))
                        "because node is not visible")))
      (scroll-into-view-if-needed node)
      (when-not (-wait-enabled node)
        (throw (cuic-ex "Can't choose options from node" (quoted (get-node-name node))
                        "because node is disabled")))
      (-exec-js "
        for (let o of this.options) {
          console.log(o)
          if ((o.selected = vals.includes(o.value)) && !this.multiple) {
            break;
          }
        }
        this.dispatchEvent(new Event('input', {bubbles: true}));
        this.dispatchEvent(new Event('change', {bubbles: true}));
        " {:vals options} node)
      nil)))

(defn add-files
  "Add files to the given node. Node must be a valid html file input
   element or otherwise an exception is thrown. If node is not in the
   view, scrolls the node into view first. Waits [[cuic.core/*timeout*]]
   until the node becomes available (visible and not disabled) or throws
   an exception if the timeout exceeds.

   The given files must be `java.io.File` instances and all of them
   must exist in the filesystem.

   ```clojure
   (require '[clojure.java.io :as io])
   (def photo (c/find \"input#photo\"))
   (c/add-files photo (io/file \"photos/profile.jpg\"))
   ```"
  [node & files]
  (rewrite-exceptions
    (check-arg [node? "dom node"] [node "input node"])
    (doseq [file files]
      (check-arg [#(instance? File %) "file instance"] [file "file"])
      (when-not (.exists ^File file)
        (throw (cuic-ex "File does not exist:" (.getName ^File file)))))
    (stale-as-ex (cuic-ex "Can't add files to node" (quoted (get-node-name node))
                          "because node does not exist anymore")
      (when-not (-js-prop node "this.matches('input[type=file]')")
        (throw (cuic-ex "Node" (quoted (get-node-name node)) "is not a file input element")))
      (when-not (-wait-visible node)
        (throw (cuic-ex "Can't add files to node" (quoted (get-node-name node))
                        "because node is not visible")))
      (scroll-into-view-if-needed node)
      (when-not (-wait-enabled node)
        (throw (cuic-ex "Can't add files to node" (quoted (get-node-name node))
                        "because node is disabled")))
      (when (seq files)
        (invoke {:cdt  (get-node-cdt node)
                 :cmd  "DOM.setFileInputFiles"
                 :args {:nodeId (get-node-id node)
                        :files  (mapv #(.getAbsolutePath ^File %) files)}}))
      node)))

;;; misc

(defn screenshot
  "Captures a screenshot from the current page and returns a byte
   array the the captured image data. Image format and quality can
   be configured by the following options:

   ```clojure
   {:format  ... ; either :jpeg or :png>
    :quality ... ; 0-100, applied only if format is :jpeg
    }
   ```

   Uses the current browser by default but it can be overrided by
   providing browser as an option to the invocation.

   ```clojure
   ;; Take PNG screenshot and save it the disk
   (let [data (c/screenshot)]
     (io/copy data (io/file \"screenshots/example.png\")))

   ;; Take JPEG screenshot with quality of 75
   (c/screenshot {:format :jpeg :quality 75})

   ;; Take screenshot from non-default browser
   (c/screenshot {:browser another-chrome})
   ```"
  ([] (rewrite-exceptions (screenshot {})))
  ([{:keys [browser
            format
            quality
            timeout]
     :or   {browser (-require-default-browser)
            format  :png
            quality 50
            timeout *timeout*}}]
   (rewrite-exceptions
     (check-arg [#{:jpeg :png} "either :jgep or :png"] [format "format"])
     (check-arg [#(and (integer? %) (<= 0 % 100)) "between 0 and 100"] [quality "quality"])
     (check-arg [nat-int? "positive integer or zero"] [timeout "timeout"])
     (check-arg [chrome? "chrome instance"] [browser "browser"])
     (let [cdt (devtools browser)
           res (invoke {:cdt     cdt
                        :cmd     "Page.captureScreenshot"
                        :args    {:format      (name format)
                                  :quality     quality
                                  :fromSurface true}
                        :timeout timeout})]
       (decode-base64 (:data res))))))
