(ns cuic.core
  "Core functions for UI queries and interactions"
  (:refer-clojure :exclude [find type name])
  (:require [clojure.core :as core]
            [clojure.string :as string]
            [cuic.chrome :refer [chrome? devtools get-current-page]]
            [cuic.internal.cdt :refer [invoke ex-code]]
            [cuic.internal.dom :refer [with-element-meta
                                       element?
                                       document?
                                       get-element-name
                                       get-custom-name
                                       get-element-cdt
                                       assoc-custom-name]]
            [cuic.internal.page :refer [navigate-to
                                        navigate-forward
                                        navigate-back
                                        set-dialog-handler]]
            [cuic.internal.runtime :refer [js-object?
                                           get-object-id
                                           eval-sync
                                           call-sync
                                           call-async
                                           get-window
                                           scroll-into-view-if-needed]]
            [cuic.internal.input :refer [mouse-move
                                         mouse-click
                                         type-text
                                         press-key]]
            [cuic.internal.html :refer [parse-document parse-element boolean-attributes]]
            [cuic.internal.util :refer [rewrite-exceptions
                                        stale-as-ex
                                        cuic-ex
                                        timeout-ex
                                        check-arg
                                        quoted
                                        decode-base64
                                        url-str?]])
  (:import (java.io File)
           (cuic TimeoutException DevtoolsProtocolException CuicException)))

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
  "Default browser that will be used for the core queries such as
   [[cuic.core/find]], [[cuic.core/document]] or [[cuic.core/window]]
   if the browser is not explicitly defined during the query
   invocation time.

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

(def ^:dynamic *base-url*
  "Base url that will be prepended to [[cuic.core/goto]] url if the
   url does not contain hostname.

   Use `binding` to assign default for e.g. single test run:

   ```clojure
   (defn local-server-test-fixture [t]
     (binding [*base-url* (str \"http://localhost:\" (get-local-server-port))]
       (t)))
   ```"
  nil)

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
  "HTML element that acts as an implicit root scope for [[cuic.core/find]]
   and [[cuic.core/query]] invocations, unless explicitly specified at query
   invocation time. Setting this value to `nil` indicates that
   queries should use document scope by default.

   Do not bind this variable directly, instead use [[cuic.core/in]]
   to define the query scope in your application code."
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

(defn set-base-url!
  "Globally resets the default base url. Useful for example REPL
   usage. See [[cuic.core/*timeout*]] for more details."
  [base-url]
  (rewrite-exceptions
    (check-arg [url-str? "valid url string"] [base-url "base url"])
    (alter-var-root #'*base-url* (constantly base-url))))

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
   `n` milliseconds."
  [ms]
  {:pre [(nat-int? ms)]}
  (Thread/sleep ms))

(defmacro wait
  "Macro that waits until the given expression yields a truthy value.
   If the expression does not yield a thruthy value within a certain
   time, throws a timeout exception. Uses [[cuic.core/*timeout*]] by
   default but the timeout value can be overrided by providing
   it as an option.

   **Attention:** the waited expression may be invoked multiple times
   so it should **not** contain any side effects!

   Supported options are:
     * `:timeout` - integer value of milliseconds until wait timeouts,
        use zero to disable timeout entirely
     * `:message` - custom error message to display in case of timeout.
        Can be either string or zero-arity function that gets called
        when the timeout occurs.

   ```clojure
   ;; Wait using defaults
   (c/wait (= 2 (get-num-result-rows)))

   ;; Wait using custom 1 sec timeout
   (c/wait (= 2 (get-num-result-rows)) {:timeout 1000})

   ;; Wait using custom error message
   (c/wait (= 2 (get-num-result-rows)) {:message \"Result rows not matching\"})

   ;; Wait using dynamic custom error message
   (c/wait (= 2 (get-num-result-rows)) {:message #(format \"Expected 2 rows but got %d\" (get-num-result-rows))})
   ```
   "
  ([expr] `(wait ~expr {}))
  ([expr opts]
   `(let [opts# (let [o# ~opts]
                  (assert (map? o#) "Options must be a map")
                  o#)
          timeout# (or (:timeout opts#) *timeout*)
          msg# (:message opts#)
          start-t# (System/currentTimeMillis)]
      (assert (nat-int? timeout#) "Timeout must be a positive integer or zero")
      (assert (or (nil? msg#)
                  (string? msg#)
                  (ifn? msg#))
              "Custom error message must be string or function producing a string")
      (loop []
        (let [val# ~expr]
          (or val#
              (if (>= (- (System/currentTimeMillis) start-t#) timeout#)
                (cond
                  (string? msg#)
                  (throw (TimeoutException. msg# val#))
                  (ifn? msg#)
                  (throw (TimeoutException. (str (apply msg# [])) val#))
                  :else
                  (throw (TimeoutException. (str "Timeout exceeded while waiting for truthy "
                                                 "value from expression: " ~(pr-str expr))
                                            val#)))
                (recur))))))))

(defn timeout-ex?
  "Return true if the given exception is caused by timeout while waiting
   for certain condition, for example element becoming visible before click
   or custom condition from [[cuic.core/wait]]."
  [ex]
  (instance? TimeoutException ex))

(defmacro in
  "Macro that runs its body using the given element as the \"root scope\"
   for queries: all matching elements must be found under the element.
   Scope must be a valid HTML element or otherwise an exception is thrown.

   ```clojure
   ;; the following codes are equivalent

   (c/in (c/find \"#header\")
     (let [items (c/query \".navi-item\")]
       ...))

   (let [header (c/find \"#header\")
         items (c/query {:in header :by \".navi-item\"})]
     ...)
   ```"
  [scope & body]
  `(rewrite-exceptions
     (let [root-elem# ~scope]
       (check-arg [element? "html element"] [root-elem# "scope root"])
       (binding [*query-scope* root-elem#]
         ~@body))))

(defn as
  "Assigns a new custom name for the given element. The assigned name
   will be displayed in REPL and in error messages but it does
   not have any effect on the JavaScript page element.

   ```clojure
   (-> (c/find \"#sidebar\")
       (c/as \"Sidebar\")
   ```

   Note that usually one should prefer `(find {:as <name> :by <sel>})`
   for assigning names instead of `(-> (find <sel>) (as <name>)` because
   then the name will be displayed also in the error message if `find`
   fails for some reason. See [[cuic.core/find]] and [[cuic.core/query]]
   for more details about name assignment at the query time."
  [element name]
  (rewrite-exceptions
    (check-arg [string? "string"] [name "element name"])
    (check-arg [element? "html element"] [element "renamed element"])
    (assoc-custom-name element name)))

(defn name
  "Returns the custom name of the given element or `nil` if the name is
   not set for the element."
  [element]
  (rewrite-exceptions
    (check-arg [element? "html element"] [element "target element"])
    (get-custom-name element)))

;;;
;;; core queries
;;;

(defn- -require-default-browser []
  (or *browser* (throw (cuic-ex "Default browser not set"))))

(defn window
  "Returns the JavaScript `window` object for the current default
   browser. Note that window can't be used as the query scope. For
   query scopes, use [[cuic.core/document]] instead.

   Default browser can be overriden by providing browser
   explicitly as a parameter."
  ([] (rewrite-exceptions (window (-require-default-browser))))
  ([browser]
   (rewrite-exceptions
     (check-arg [chrome? "chrome instance"] [browser "given browser"])
     (get-window (get-current-page browser)))))

(defn document
  "Returns the JavaScript `document` object for the current default
   browser. Default browser can be overriden by providing browser
   explicitly as a parameter."
  ([] (rewrite-exceptions (document (-require-default-browser))))
  ([browser]
   (rewrite-exceptions
     (check-arg [chrome? "chrome instance"] [browser "given browser"])
     (eval-sync {:page (get-current-page browser)
                 :code "return document"}))))

(defn active-element
  "Returns the active element (HTML element) for the current default
   browser or `nil` if there are no active element in the page at the
   moment. Default browser can be overriden by providing browser explicitly
   as a parameter."
  ([] (rewrite-exceptions (active-element (-require-default-browser))))
  ([browser]
   (rewrite-exceptions
     (stale-as-ex (cuic-ex "Couldn't get active element")
       (check-arg [chrome? "chrome instance"] [browser "given browser"])
       (eval-sync {:page (get-current-page browser)
                   :code "return document.activeElement"})))))

(defn find
  "Tries to find **exactly one** html element by the given css
   selector and throws an exception if the element wasn't found **or**
   there are more than one element matching the selector.

   If the desired element is not found immediately, `find` waits until
   the element appears in the DOM. If the element does not appear within a
   certain time period, `find` throws an exception. By default, the wait
   timeout is [[cuic.core/*timeout*]] but it can be overrided per invocation.

   Basic usage:
   ```clojure
   (def search-input (c/find \".navi input.search\")
   ```

   In addition to plain css selector, `find` can be invoked with
   more information by using a map form:
   ```clojure
   (find {:by      <selector>  ; mandatory - CSS selector for the query
          :in      <scope>     ; optional - root scope for query, defaults to document see cuic.core/in
          :as      <name>      ; optional - name of the result element, see cuic.core/as
          :when    <predicate> ; optional - predicate function for extra conditions that the queried element must satisfy
          :timeout <ms>        ; optional - wait timeout in ms, defaults to cuic.core/*timeout*
          })

   ;; examples

   (c/find {:by \".navi input.search\"
            :as \"Quick search input\"})

   (c/find {:by   \"button\"
            :when #(re-find #\"Save\" (c/text-content %))
            :as   \"Save button\"})

   (c/find {:in      (find \"#sidebar\")
            :by      \".logo\"
            :timeout 10000})
   ```"
  [selector]
  (rewrite-exceptions
    (check-arg [#(or (string? %) (map? %)) "string or map"] [selector "selector"])
    (loop [selector selector]
      (if (map? selector)
        (let [{:keys [in by as timeout]
               :or   {timeout *timeout*}} selector
              pred (get selector :when identity)
              ctx (or in *query-scope* (document))
              _ (check-arg [element? "html element"] [ctx "context"])
              _ (check-arg [string? "string"] [by "selector"])
              _ (check-arg [ifn? "function"] [pred "predicate"])
              _ (check-arg [#(or (string? %) (nil? %)) "string"] [as "alias"])
              _ (check-arg [nat-int? "positive integer or zero"] [timeout "timeout"])
              start-t (System/currentTimeMillis)]
          (loop []
            (let [nodes (->> (call-sync {:code "return Array.from(this.querySelectorAll(s))"
                                         :this ctx
                                         :args {:s by}})
                             (map #(with-element-meta % ctx as by))
                             (filter pred))
                  elapsed (- (System/currentTimeMillis) start-t)]
              (case (count nodes)
                1 (first nodes)
                0 (if (< elapsed timeout)
                    (do (sleep (min 100 (- *timeout* elapsed)))
                        (recur))
                    (throw (timeout-ex "Could not find element" (quoted as)
                                       "from" (quoted (get-element-name ctx))
                                       "with selector" (quoted by)
                                       (when (:when selector) "and custom predicate")
                                       "in" timeout "milliseconds")))
                (throw (cuic-ex "Found too many" (str "(" (count nodes) ")") (quoted as)
                                "html elements from" (quoted (get-element-name ctx))
                                "with selector" (quoted by)
                                (when (:when selector) "and custom predicate")))))))
        (recur {:by selector})))))

(defn query
  "Works like [[cuic.core/find]] but returns `0..n` elements matching
   the given css selector. In case of no results, `nil` will be returned.
   Otherwise the return value is a vector of matching HTML elements.

   Basic usage:
   ```clojure
   (def navi-items
     (c/query \".navi .item\")
   ```

   In addition to plain css selector, `query` can be invoked with
   more information by using a map form:
   ```clojure
   (query {:by   <selector>  ; mandatory - CSS selector for the query
           :in   <scope>     ; optional - root scope for query, defaults to document see cuic.core/in
           :when <predicate> ; optional - predicate function for extra conditions that the queried elements must satisfy
           :as   <name>      ; optional - name for the result elements
           })

   ;; examples

   (c/query {:by \".navi .item\"
             :as \"Navigation item\"})

   (c/find {:in (find \"#sidebar\")
            :by \".item\"
            :as \"Sidebar item\"})
   ```

   Note that unlike [[cuic.core/find]], [[cuic.core/query]] **does not
   wait** for the results: if there are zero elements matching the given
   selector at the query time, the result will be `nil`. If results are
   expected, use [[cuic.core/query]] in conjunction with [[cuic.core/wait]],
   for example:

   ```clojure
   (c/wait (c/query \".navi .item\"))
   ```"
  [selector]
  (rewrite-exceptions
    (check-arg [#(or (string? %) (map? %)) "string or map"] [selector "selector"])
    (loop [selector selector]
      (if (map? selector)
        (let [{:keys [in by as]} selector
              ctx (or in *query-scope* (document))
              pred (get selector :when identity)
              _ (check-arg [element? "html element"] [ctx "context"])
              _ (check-arg [string? "string"] [by "selector"])
              _ (check-arg [ifn? "function"] [pred "predicate"])
              _ (check-arg [#(or (string? %) (nil? %)) "string"] [as "alias"])]
          (->> (call-sync {:code "return Array.from(this.querySelectorAll(s))"
                           :this ctx
                           :args {:s by}})
               (map #(with-element-meta % ctx as by))
               (filterv pred)
               (not-empty)))
        (recur {:by selector})))))

;;;
;;; js code execution
;;;

(defn eval-js
  "Evaluates the given JavaScript expression with the given `this` binding
   and returns the expression's result value. The evaluated expression may
   be parametrized with arguments from the Clojure code.

   By default `this` refers to the [[cuic.core/window]] of the current
   default browser but it may be rebound to any JavaScript object
   reference (obtained from prior invocations or queries).

   The given input arguments must be either serializable JSON values or
   JavaScript object references. The return value is serialized back
   to Clojure data structures and non-serializable JavaScript objects
   are returned back as *JavaScript object references*. Accessing the
   properties of those references is only possible by using
   either [[cuic.core/eval-js]] or [[cuic.core/exec-js]].

   ```clojure
   ;; Return the title of the current browser window
   (c/eval-js \"document.title\")

   ;; Return an async value
   (c/eval-js \"await new Promise(resolve => setTimeout(() => resolve('tsers'), 500))\")

   ;; Increment the given list values in JS side and return them
   ;; bac as CLJ vector
   (c/eval-js \"xs.map(x => x + 1)\" {:xs [1 2 3]})

   ;; Perform some arithmetics in JS side
   (c/eval-js \"a + b.num\" {:a 1000 :b {:num 337}})

   ;; Get placeholder value from the query input
   (let [q (c/find \"#query\")]
     (c/eval-js \"this.placeholder\" {} q))
   ```

   See also [[cuic.core/exec-js]] if you want to execute statements
   in the JavaScript side."
  ([expr]
   (rewrite-exceptions (eval-js expr {} (window))))
  ([expr args]
   (rewrite-exceptions (eval-js expr args (window))))
  ([expr args this]
   (rewrite-exceptions
     (check-arg [string? "string"] [expr "expression"])
     (check-arg [map? "map"] [args "call arguments"])
     (check-arg [js-object? "javascript object"] [this "this binding"])
     (stale-as-ex (cuic-ex "Can't evaluate JavaScript expression on" (quoted this)
                           "because it does not exist anymore")
       (call-async {:code (str "return (" expr ");") :this this :args args})))))

(defn exec-js
  "Executes the given JavaScript function bodu with the given `this`
   binding. The executed body may be parametrized with arguments from
   the Clojure code and it can return a value by using JavaScript's
   `return` statement at the end of the body. The function body may
   also `await` async values effects - Clojure invocation will block
   until the async result is resolved.

   By default `this` refers to the [[cuic.core/window]] of the current
   default browser but it may be rebound to any JavaScript object
   reference (obtained from prior invocations or queries).

   The given input arguments must be either serializable JSON values or
   JavaScript object references. The return value is serialized back
   to Clojure data structures and non-serializable JavaScript objects
   are returned back as *JavaScript object references*. Accessing the
   properties of those references is only possible by using
   either [[cuic.core/eval-js]] or [[cuic.core/exec-js]].

   ```clojure
   ;; Reset the document title
   (c/exec-js \"document.title = 'tsers'\")

   ;; Reset the input placeholder value
   (let [input (c/find \"#my-input\")]
     (c/exec-js \"this.setAttribute('placeholder', text)\" {:text \"tsers\"} input))

   ;; Reset the document title and return the prior value
   (c/exec-js \"const prev = document.title;
                document.title = 'tsers';
                return prev;\")
   ```

   See also [[cuic.core/eval-js]] if you only want to query data
   from the JavaScript side without performing any side-effects.
   "
  ([body]
   (rewrite-exceptions (exec-js body {} (window))))
  ([body args]
   (rewrite-exceptions (exec-js body args (window))))
  ([body args this]
   (rewrite-exceptions
     (check-arg [string? "string"] [body "function body"])
     (check-arg [map? "map"] [args "call arguments"])
     (check-arg [js-object? "javascript object"] [this "this binding"])
     (stale-as-ex (cuic-ex "Can't execute JavaScript code on" (quoted this)
                           "because it does not exist anymore")
       (call-async {:code body :this this :args args})))))

;;;
;;; properties
;;;

(defn- -js-prop
  ([element js-expr] (-js-prop element js-expr {}))
  ([element js-expr args]
   (let [code (str "return " (string/replace js-expr #"\n\s+" " ") ";")]
     (call-sync {:code code :this element :args args}))))

(defn- -bb [element]
  (call-sync
    {:code "
       let r = this.getBoundingClientRect();
       return {
         top: r.top,
         left: r.left,
         width: r.width,
         height: r.height
       };"
     :this element}))

(defn client-rect
  "Returns the bounding client rect for the given element as a map of:
   ```clojure
   {:top    <number>
    :left   <number>
    :width  <number>
    :height <number>}
   ```

   See https://developer.mozilla.org/en-US/docs/Web/API/Element/getBoundingClientRect
   for more info."
  [element]
  (rewrite-exceptions
    (check-arg [element? "html element"] [element "element"])
    (stale-as-ex (cuic-ex "Can't get client rect from element" (quoted (get-element-name element))
                          "because it does not exist anymore")
      (-bb element))))

(defn visible?
  "Returns boolean whether the given element is visible in DOM or not"
  [element]
  (rewrite-exceptions
    (check-arg [element? "html element"] [element "element"])
    (stale-as-ex (cuic-ex "Can't check visibility of element" (quoted (get-element-name element))
                          "because it does not exist anymore")
      (-js-prop element "!!this.offsetParent"))))

(defn in-viewport?
  "Returns boolean whether the given element is currently visible and
   in the viewport"
  [element]
  (rewrite-exceptions
    (check-arg [element? "html element"] [element "element"])
    (stale-as-ex (cuic-ex "Can't check visibility of element" (quoted (get-element-name element))
                          "because it does not exist anymore")
      (-js-prop element "!!this.offsetParent && __CUIC__.isInViewport(this)"))))

(defn parent
  "Returns the parent element of the given html element or `nil` if
   the element does not have any parent.

   See https://developer.mozilla.org/en-US/docs/Web/API/Node/parentElement
   for more details."
  [element]
  (rewrite-exceptions
    (check-arg [element? "html element"] [element "element"])
    (stale-as-ex (cuic-ex "Can't get parent of element" (quoted (get-element-name element))
                          "because it does not exist anymore")
      (let [parent (call-sync {:code "return this.parentElement;" :this element})]
        (when parent
          (with-element-meta parent nil nil nil))))))

(defn offset-parent
  "Returns the offset parent element of the given html element or `nil`
   if the element does not have any offset parent.

   See https://developer.mozilla.org/en-US/docs/Web/API/HTMLElement/offsetParent
   for more details."
  [element]
  (rewrite-exceptions
    (check-arg [element? "html element"] [element "element"])
    (stale-as-ex (cuic-ex "Can't get offset parent of element" (quoted (get-element-name element))
                          "because it does not exist anymore")
      (let [parent (call-sync {:code "return this.offsetParent;" :this element})]
        (when parent
          (with-element-meta parent nil nil nil))))))

(defn closest
  "Returns the closest ancestor element that matches the given css
   selector or `nil` if there are no matches.

   See https://developer.mozilla.org/en-US/docs/Web/API/Element/closest
   for more details."
  [element selector]
  (rewrite-exceptions
    (check-arg [element? "html element"] [element "element"])
    (check-arg [string? "string"] [selector "selector"])
    (stale-as-ex (cuic-ex "Can't get closest ancestor of element" (quoted (get-element-name element))
                          "because it does not exist anymore")
      (let [ancestor (call-sync {:code "return this.closest(css);"
                                 :this element
                                 :args {:css selector}})]
        (when ancestor
          (with-element-meta ancestor nil nil nil))))))

(defn children
  "Returns a vector of children of the given html element or
   `nil` if the element does not have any children.

   See https://developer.mozilla.org/en-US/docs/Web/API/ParentNode/children
   for more details"
  [element]
  (rewrite-exceptions
    (check-arg [element? "html element"] [element "element"])
    (stale-as-ex (cuic-ex "Can't get children of element" (quoted (get-element-name element))
                          "because it does not exist anymore")
      (not-empty (-js-prop element "Array.from(this.children)")))))

(defn inner-text
  "Returns the inner text of the given html element. See
   https://developer.mozilla.org/en-US/docs/Web/API/HTMLElement/innerText
   for more info."
  [element]
  (rewrite-exceptions
    (check-arg [element? "html element"] [element "element"])
    (stale-as-ex (cuic-ex "Can't get inner text from element" (quoted (get-element-name element))
                          "because it does not exist anymore")
      (-js-prop element "this.innerText"))))

(defn text-content
  "Returns the text content of the given html element. See
   https://developer.mozilla.org/en-US/docs/Web/API/Node/textContent
   for more info."
  [element]
  (rewrite-exceptions
    (check-arg [element? "html element"] [element "element"])
    (stale-as-ex (cuic-ex "Can't get text content from element" (quoted (get-element-name element))
                          "because it does not exist anymore")
      (-js-prop element "this.textContent"))))

(defn outer-html
  "Returns the outer HTML of the given element in a [hiccup](https://github.com/weavejester/hiccup).
   form. Comments use `:-#comment` tag and CDATA nodes will use
   `:-#data` tag.

   See https://developer.mozilla.org/en-US/docs/Web/API/Element/outerHTML
   for more details."
  [element]
  (rewrite-exceptions
    (check-arg [element? "html element"] [element "element"])
    (stale-as-ex (cuic-ex "Can't get outer html from element" (quoted (get-element-name element))
                          "because it does not exist anymore")
      (if (document? element)
        (-> (-js-prop element "this.querySelector('html').outerHTML")
            (parse-document))
        (-> (-js-prop element "this.outerHTML")
            (parse-element))))))

(defn value
  "Returns the current string value of the given input/select/textarea
   element. Throws an exception if the target element is not a valid
   input element."
  [element]
  (rewrite-exceptions
    (check-arg [element? "html element"] [element "element"])
    (stale-as-ex (cuic-ex "Can't get value from element" (quoted (get-element-name element))
                          "because it does not exist anymore")
      (when-not (-js-prop element "'value' in this")
        (throw (cuic-ex (quoted (get-element-name element)) "is not a valid input element")))
      (-js-prop element "this.value"))))

(defn options
  "Returns a list of options `{:keys [value text selected]}` for the
   given element. Throws an exception if the element is not a select element."
  [element]
  (rewrite-exceptions
    (check-arg [element? "html element"] [element "element"])
    (stale-as-ex (cuic-ex "Can't get options from element" (quoted (get-element-name element))
                          "because it does not exist anymore")
      (when (not= "SELECT" (-js-prop element "this.tagName"))
        (throw (cuic-ex (quoted (get-element-name element)) "is not a valid select element")))
      (-js-prop element "Array.prototype.slice
                      .call(this.options || [])
                      .map(({value, text, selected}) => ({ value, text, selected }))"))))

(defn attributes
  "Returns element's HTML attributes as a map of `{:attr-name \"attr-value\"}`.
   Boolean attribute values will be converted to `true` if they are present.

   ```clojure
   ;; html element <div id='foo' data-val='1' class='foo bar'></div>
   (c/attributes (c/find \"#foo\"))
   ; =>
   {:id       \"foo\"
    :data-val \"1\"
    :class    \"foo bar\"}
   ```"
  [element]
  (rewrite-exceptions
    (check-arg [element? "html element"] [element "element"])
    (stale-as-ex (cuic-ex "Can't get attributes from element" (quoted (get-element-name element))
                          "because it does not exist anymore")
      (->> (-js-prop element "[...this.attributes].map(a => [a.name, a.value.toString()])")
           (map (fn [[k v]] [(keyword k) (if (contains? boolean-attributes k) true v)]))
           (into {})))))

(defn classes
  "Returns a set of css classes (as strings) for the given element. Returns
   an empty set if element does not have any classes.

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
  [element]
  (rewrite-exceptions
    (check-arg [element? "html element"] [element "element"])
    (as-> (attributes element) $
          (get $ :class "")
          (string/split $ #"\s+")
          (map string/trim $)
          (remove string/blank? $)
          (set $))))

(defn has-class?
  "Returns boolean whether the given element has the tested
   css class or not."
  [element class]
  (rewrite-exceptions
    (check-arg [element? "html element"] [element "element"])
    (check-arg [string? "string"] [class "tested class name"])
    (contains? (classes element) class)))

(defn matches?
  "Returns boolean whether the given element matches the tested css
   selector or not. Throws an exception if selector is not valid."
  [element selector]
  (rewrite-exceptions
    (check-arg [element? "html element"] [element "element"])
    (check-arg [string? "string"] [selector "tested css selector"])
    (stale-as-ex (cuic-ex "Can't match css selector to element" (quoted (get-element-name element))
                          "because it does not exist anumore")
      (let [match (-js-prop element "(function(n){try{return n.matches(s)}catch(_){}})(this)" {:s selector})]
        (when (nil? match)
          (throw (cuic-ex "The tested css selector" (quoted selector) "is not valid")))
        match))))

(defn has-focus?
  "Returns boolean whether the given element has focus or not."
  [element]
  (rewrite-exceptions
    (check-arg [element? "html element"] [element "element"])
    (stale-as-ex (cuic-ex "Can't resolve focus from element" (quoted (get-element-name element))
                          "because it does not exist anymore")
      (-js-prop element "document.activeElement === this"))))

(defn checked?
  "Returns boolean whether the given element is checked or not. Throws
   an exception if the target element is not a valid input element."
  [element]
  (rewrite-exceptions
    (check-arg [element? "html element"] [element "element"])
    (stale-as-ex (cuic-ex "Can't resolve checked status from element" (quoted (get-element-name element))
                          "because it does not exist anymore")
      (when-not (-js-prop element "'checked' in this")
        (throw (cuic-ex (quoted (get-element-name element)) "is not a valid input element")))
      (-js-prop element "!!this.checked"))))

(defn disabled?
  "Returns boolean whether the given element is disabled or not."
  [element]
  (rewrite-exceptions
    (check-arg [element? "html element"] [element "element"])
    (stale-as-ex (cuic-ex "Can't resolve disabled status from element" (quoted (get-element-name element))
                          "because it does not exist anymore")
      (-js-prop element "!!this.disabled"))))

;;;
;;; actions
;;;

(defn- -wait-visible [element]
  (try
    (wait (-js-prop element "!!this.offsetParent"))
    (catch TimeoutException _
      false)))

(defn- -wait-enabled [element]
  (try
    (wait (-js-prop element "!this.disabled"))
    (catch TimeoutException _
      false)))

(defn goto
  "Navigates browser to the given url, equivalent to user typing
   url to the browser's address bar and pressing enter. Url **must**
   contain the protocol. Reloads the page, even if navigating to
   the current page. Waits until the page is loaded or timeout
   exceeds. Uses [[cuic.core/*timeout*]] by default but it can be
   overrided by giving the timeout as an option to the invocation.

   If [[cuic.core/*base-url*]] is set, it'll be prepended to the
   given url if the given url does not contain a hostname.

   Uses the current browser by default but it can be overrided
   by providing browser as an option to the invocation.

   ```clojure
   ;; Use default browser and timeout
   (c/goto \"https://clojuredocs.org\")

   ;; Use non-default browser
   (c/goto \"https://clojuredocs.org\" {:browser another-chrome})

   ;; Use non-default timeout
   (c/goto \"https://clojuredocs.org\" {:timeout 300000})

   ;; Go to https://clojuredocs.org/clojure.core/map using base url
   (binding [c/*base-url* \"https://clojuredocs.org\"]
     (c/goto \"/clojure.core/map\"))
   ```"
  ([url] (goto url {}))
  ([url opts]
   (rewrite-exceptions
     (let [{:keys [timeout browser]
            :or   {timeout *timeout*}} opts
           browser (or browser (-require-default-browser))
           full-url (if (and (string? url)
                             (not (url-str? url))
                             *base-url*)
                      (str *base-url* url)
                      url)]
       (check-arg [url-str? "valid url string"] [full-url "url"])
       (check-arg [nat-int? "positive integer or zero"] [timeout "timeout"])
       (navigate-to (get-current-page browser) full-url timeout))
     nil)))

(defn go-back
  "Simulates browser's back button click. Returns boolean whether the
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
            :or   {timeout *timeout*}} opts
           browser (or browser (-require-default-browser))]
       (check-arg [nat-int? "positive integer or zero"] [timeout "timeout"])
       (boolean (navigate-back (get-current-page browser) timeout))))))

(defn go-forward
  "Simulates browser's forward button click. Returns boolean whether the
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
            :or   {timeout *timeout*}} opts
           browser (or browser (-require-default-browser))]
       (check-arg [nat-int? "positive integer or zero"] [timeout "timeout"])
       (boolean (navigate-forward (get-current-page browser) timeout))))))

(defn type
  "Simulates keyboard typing. Characters are pressed and released one by one
   using the provided typing speed. Typing speed uses [[cuic.core/*typing-speed*]]
   by default but it can be overrided by giving it as a `:speed` option
   to the invocation.

   Note that this function is quite low level and it **does not** focus
   the typed text on any element. See [[cuic.core/fill]] for a more high-level
   alternative.

   Uses the current browser by default but it can be overrided
   by providing `:browser` option to the invocation.

   ```clojure
   ;; Type some text
   (c/type \"Tsers!\")

   ;; Type some text very fast
   (c/type \"Tsers!\" {:speed :tycitys})

   ;; Type some text to non-default browser
   (c/type \"Tsers!\" {:browser another-chrome})
   ```"
  ([text] (type text {}))
  ([text opts]
   (rewrite-exceptions
     (let [{:keys [browser speed]
            :or   {speed *typing-speed*}} opts
           browser (or browser (-require-default-browser))]
       (check-arg [string? "string"] [text "typed text"])
       (check-arg [chrome? "chrome instance"] [browser "browser"])
       (type-text (devtools browser) text (-chars-per-minute speed))
       nil))))

(defn press
  "Simulates a key press of single key. The pressed key must be a symbol
   of a valid keycode with optional modifiers separated by '+' (see
   examples below). You can use e.g. https://keycode.info to get the desired
   key code (`event.code`) for the pressed key. The complete list of
   the available key codes can be found from
   https://developer.mozilla.org/en-US/docs/Web/API/KeyboardEvent/keyCode

   Uses the current browser by default but it can be overrided
   by providing browser as a second parameter.

   ```clojure
   ;; Focus on next tab index element
   (c/press 'Tab)

   ;; Focus on previous tab index element
   (c/press 'Shift+Tab)

   ;; Focus on next tab index element in custom browser
   (c/press 'Tab {:browser another-chrome})
   ```"
  ([key] (rewrite-exceptions (press key {})))
  ([key opts]
   (rewrite-exceptions
     (let [browser (or (:browser opts) (-require-default-browser))]
       (check-arg [simple-symbol? "symbol"] [key "pressed key"])
       (check-arg [chrome? "chrome instance"] [browser "browser"])
       (press-key (devtools browser) key)
       nil))))

(defn scroll-into-view
  "Scrolls the given element into the viewport if it is not in the view
   already. Waits [[cuic.core/*timeout*]] until the element becomes visible
   or throws an exception if the timeout exceeds.

   Returns the target element for threading."
  [element]
  (rewrite-exceptions
    (check-arg [element? "html element"] [element "target element"])
    (stale-as-ex (cuic-ex "Can't scroll element" (quoted (get-element-name element))
                          "into view because it does not exist anymore")
      (when-not (-wait-visible element)
        (throw (timeout-ex "Can't scroll element" (quoted (get-element-name element))
                           "into view because it is not visible")))
      (scroll-into-view-if-needed element)
      element)))

(defn hover
  "First scrolls the given element into the viewport (if needed) and then moves
   the mouse over the element. Waits [[cuic.core/*timeout*]] until the element
   becomes visible or throws an exception if the timeout exceeds.

   Throws an exception if element is not hoverable (it has either zero
   width or zero height).

   Returns the target element for threading."
  [element]
  (rewrite-exceptions
    (check-arg [element? "html element"] [element "hover target"])
    (stale-as-ex (cuic-ex "Can't hover over element" (quoted (get-element-name element))
                          "because it does not exist anymore")
      (when-not (-wait-visible element)
        (throw (timeout-ex "Can't hover over element" (quoted (get-element-name element))
                           "because it is not visible")))
      (scroll-into-view-if-needed element)
      (let [{:keys [top left width height]} (-bb element)
            cdt (get-element-cdt element)
            x (+ left (/ width 2))
            y (+ top (/ height 2))]
        (when (or (zero? width)
                  (zero? height))
          (throw (cuic-ex (quoted (get-element-name element)) "is not hoverable")))
        (mouse-move cdt {:x x :y y})
        element))))

(defn click
  "First scrolls the given element into the viewport (if needed) and then
   clicks the element once. Waits [[cuic.core/*timeout*]] until the element
   becomes visible or throws an exception if the timeout exceeds.

   Throws an exception if element is not clickable (it has either zero
   width or zero height or it is disabled).

   Returns the clicked element for threading.

   ```clojure
   (c/click (c/find \"button#save\"))
   ```"
  [element]
  (rewrite-exceptions
    (check-arg [element? "html element"] [element "clicked element"])
    (stale-as-ex (cuic-ex "Can't click element" (quoted (get-element-name element))
                          "because it does not exist anymore")
      (when-not (-wait-visible element)
        (throw (timeout-ex "Can't click element" (quoted (get-element-name element))
                           "because it is not visible")))
      (scroll-into-view-if-needed element)
      (when-not (-wait-enabled element)
        (throw (timeout-ex "Can't click element" (quoted (get-element-name element))
                           "because it is disabled")))
      (let [{:keys [top left width height]} (-bb element)
            cdt (get-element-cdt element)
            x (+ left (/ width 2))
            y (+ top (/ height 2))]
        (when (or (zero? width)
                  (zero? height))
          (throw (cuic-ex (quoted (get-element-name element)) "is not clickable")))
        (mouse-move cdt {:x x :y y})
        (mouse-click cdt {:x x :y y :button :left :clicks 1})
        element))))

(defn double-click
  "First scrolls the given element into the viewport (if needed) and then
   double-clicks the element twice. Waits [[cuic.core/*timeout*]] until the
   element becomes visible or throws an exception if the timeout exceeds.

   Throws an exception if element is not clickable (it has either zero
   width or zero height or it is disabled).

   Returns the clicked element for threading.

   ```clojure
   (c/double-click (c/find \"button#save\"))
   ```"
  [element]
  (rewrite-exceptions
    (check-arg [element? "html element"] [element "clicked element"])
    (stale-as-ex (cuic-ex "Can't click element" (quoted (get-element-name element))
                          "because it does not exist anymore")
      (when-not (-wait-visible element)
        (throw (timeout-ex "Can't double-click element" (quoted (get-element-name element))
                           "because it is not visible")))
      (scroll-into-view-if-needed element)
      (when-not (-wait-enabled element)
        (throw (timeout-ex "Can't double-click element" (quoted (get-element-name element))
                           "because it is disabled")))
      (let [{:keys [top left width height]} (-bb element)
            cdt (get-element-cdt element)
            x (+ left (/ width 2))
            y (+ top (/ height 2))]
        (when (or (zero? width)
                  (zero? height))
          (throw (cuic-ex (quoted (get-element-name element)) "is not clickable")))
        (mouse-move cdt {:x x :y y})
        (mouse-click cdt {:x x :y y :button :left :clicks 2})
        element))))

(defn focus
  "First scrolls the given element into the viewport (if needed) and then
   focuses on the element. Waits [[cuic.core/*timeout*]] until the element
   becomes visible or throws an exception if the timeout exceeds.

   Returns the focused element for threading."
  [element]
  (rewrite-exceptions
    (check-arg [element? "html element"] [element "focused element"])
    (stale-as-ex (cuic-ex "Can't focus on element" (quoted (get-element-name element))
                          "because it does not exist anymore")
      (when-not (-wait-visible element)
        (throw (timeout-ex "Can't focus on element" (quoted (get-element-name element))
                           "because it is not visible")))
      (scroll-into-view-if-needed element)
      (when-not (-wait-enabled element)
        (throw (timeout-ex "Can't focus on element" (quoted (get-element-name element))
                           "because it is disabled")))
      (call-sync {:code "this.focus()"
                  :this element})
      element)))

(defn select-all-text
  "First scrolls the given element into the viewport (if needed) and then
   selects all text from the element. element must be an input/textarea
   element or otherwise an exception is thrown. Waits [[cuic.core/*timeout*]]
   until the element becomes visible or throws an exception if the timeout
   exceeds.

   Returns the target element for threading."
  [element]
  (rewrite-exceptions
    (check-arg [element? "html element"] [element "selected element"])
    (stale-as-ex (cuic-ex "Can't select text from element" (quoted (get-element-name element))
                          "because it does not exist anymore")
      (when-not (-js-prop element "typeof this.select === 'function'")
        (throw (cuic-ex (quoted (get-element-name element)) "is not a valid input element")))
      (when-not (-wait-visible element)
        (throw (timeout-ex "Can't select text from element" (quoted (get-element-name element))
                           "because it is not visible")))
      (scroll-into-view-if-needed element)
      (when-not (-wait-enabled element)
        (throw (timeout-ex "Can't select text from element" (quoted (get-element-name element))
                           "because it is disabled")))
      (call-sync {:code "this.select()" :this element})
      element)))

(defn clear-text
  "First scrolls the given element into the viewport (if needed) and then
   clears all text from the element. The element must be an input/textarea
   or otherwise an exception is thrown. Waits [[cuic.core/*timeout*]] until
   the element becomes visible or throws an exception if the timeout exceeds.

   Returns the target element for threading."
  [element]
  (rewrite-exceptions
    (check-arg [element? "html element"] [element "cleared element"])
    (stale-as-ex (cuic-ex "Can't clear text from element" (quoted (get-element-name element))
                          "because it does not exist anymore")
      (when-not (-js-prop element "typeof this.select === 'function'")
        (throw (cuic-ex (quoted (get-element-name element)) "is not a valid input element")))
      (when-not (-wait-visible element)
        (throw (timeout-ex "Can't clear text from element" (quoted (get-element-name element))
                           "because it is not visible")))
      (scroll-into-view-if-needed element)
      (when-not (-wait-enabled element)
        (throw (timeout-ex "Can't clear text from element" (quoted (get-element-name element))
                           "because it is disabled")))
      (call-sync {:code "this.select()" :this element})
      (press-key (get-element-cdt element) 'Backspace)
      element)))

(defn fill
  "Fills the given text to the given `input` or `textarea` element,
   clearing any previous text before typing the new text. If the element is not
   in the viewport, scrolls it into the view first. Waits [[cuic.core/*timeout*]]
   until the element becomes fillable (visible and not disabled) or
   throws an exception if the timeout exceeds.

   Uses [[cuic.core/*typing-speed*]] by default but it can be overrided
   by giving the speed as a third parameter. See [[cuic.core/*typing-speed*]]
   for valid values. Only text is allowed: if you need to type keycodes,
   use [[cuic.core/press]] instead. The exceptions are newline character
   `\\n` and tab `\\t` that will be interpreted as their respective key
   presses.

   Returns the filled element for threading.

   ```clojure
   (def comment (c/find \"input.comment\"))

   ;; Fill some text to comment and commit by pressing enter
   (c/fill comment \"Tsers\")
   (c/press 'Enter)

   ;; Same in terser form
   (c/fill comment \"Tsers\\n\")

   ;; Fill text fast
   (c/fill comment \"Tsers\" {:speed :tycitys})
   ```"
  ([element text] (fill element text *typing-speed*))
  ([element text opts]
   (rewrite-exceptions
     (let [{:keys [speed] :or {speed *typing-speed*}} opts
           cdt (get-element-cdt element)
           chars-per-min (-chars-per-minute speed)]
       (check-arg [element? "html element"] [element "filled element"])
       (check-arg [string? "string"] [text "typed text"])
       (stale-as-ex (cuic-ex "Can't fill element" (quoted (get-element-name element))
                             "because it does not exist anymore")
         (when-not (-wait-visible element)
           (throw (timeout-ex "Can't fill element" (quoted (get-element-name element))
                              "because it is not visible")))
         (scroll-into-view-if-needed element)
         (when-not (-wait-enabled element)
           (throw (timeout-ex "Can't fill element" (quoted (get-element-name element))
                              "because it is disabled")))
         (call-sync {:code "this.select()" :this element})
         (press-key cdt 'Backspace)
         (type-text cdt text chars-per-min)
         element)))))

(defn select
  "Selects the given option or options (strings) from the given element.
   The element must be a html select or otherwise an exception will be
   thrown. If element is not in the viewport, scrolls it into the view first.
   Waits [[cuic.core/*timeout*]] until the element becomes fillable (visible
   and not disabled) or throws an exception if the timeout exceeds.

   In a case of single option, provide the selected option as a string. In a case
   of multiple options, provide the selected options as a sequence of strings.

   Returns the target element for threading.

   ```clojure
   ;; Select single option
   (c/select my-html-select \"foo\")

   ;; Select multiple options
   (c/select my-html-multiselect [\"foo\" \"bar\"])
   ```"
  [element option-or-options]
  (rewrite-exceptions
    (let [options (if (coll? option-or-options) option-or-options [option-or-options])]
      (check-arg [element? "html element"] [element "updated element"])
      (check-arg [#(every? string? %) "strings"] [options "selected options"])
      (stale-as-ex (cuic-ex "Can't choose options from element" (quoted (get-element-name element))
                            "because it does not exist anymore")
        (when (not= "SELECT" (-js-prop element "this.tagName"))
          (throw (cuic-ex (quoted (get-element-name element)) "is not a valid select element")))
        (when-not (-wait-visible element)
          (throw (timeout-ex "Can't choose options from element" (quoted (get-element-name element))
                             "because it is not visible")))
        (scroll-into-view-if-needed element)
        (when-not (-wait-enabled element)
          (throw (timeout-ex "Can't choose options from element" (quoted (get-element-name element))
                             "because it is disabled")))
        (call-sync
          {:code "
           for (let o of this.options) {
             console.log(o)
             if ((o.selected = vals.includes(o.value)) && !this.multiple) {
               break;
             }
           }
           this.dispatchEvent(new Event('input', {bubbles: true}));
           this.dispatchEvent(new Event('change', {bubbles: true}));
           "
           :args {:vals (vec options)}
           :this element})
        nil))))

(defn add-files
  "Adds files to the given element. The element must be a valid html file
   input or otherwise an exception is thrown. If the element is not in the
   viewport, scrolls it into the view first. Waits [[cuic.core/*timeout*]]
   until the element becomes available (visible and not disabled) or throws
   an exception if the timeout exceeds.

   The given files must be `java.io.File` instances and all of them
   must exist in the filesystem.

   By default, the target input must be visible. However, because it's very
   common in modern web applications that file inputs are hidden due to their
   bleak appearance, you can disable the visibility requirement by providing
   an `:allow-hidden? true` option. The input must still be enabled though.

   ```clojure
   (require '[clojure.java.io :as io])
   (def photo (c/find \"input#photo\"))
   (c/add-files photo [(io/file \"photos/profile.jpg\")])

   ;; add profile picture to a hidden input
   (c/add-files hidden-input [(io/file \"photos/profile.jpg\")] {:allow-hidden? true})
   ```"
  ([element files]
   (rewrite-exceptions (add-files element files {})))
  ([element files {:keys [allow-hidden?] :or {allow-hidden? false}}]
   (rewrite-exceptions
     (check-arg [element? "html element"] [element "input element"])
     (doseq [file files]
       (check-arg [#(instance? File %) "file instance"] [file "file"])
       (when-not (.exists ^File file)
         (throw (cuic-ex "File does not exist:" (.getName ^File file)))))
     (check-arg [boolean? "boolean"] [allow-hidden? "allow hidden flag"])
     (stale-as-ex (cuic-ex "Can't add files to element" (quoted (get-element-name element))
                           "because it does not exist anymore")
       (when-not (-js-prop element "this.matches('input[type=file]')")
         (throw (cuic-ex (quoted (get-element-name element)) "is not a file input element")))
       (when-not (or allow-hidden? (-wait-visible element))
         (throw (timeout-ex "Can't add files to element" (quoted (get-element-name element))
                            "because it is not visible")))
       (when (-js-prop element "!!this.offsetParent")
         (scroll-into-view-if-needed element))
       (when-not (-wait-enabled element)
         (throw (timeout-ex "Can't add files to element" (quoted (get-element-name element))
                            "because it is disabled")))
       (when (seq files)
         (try
           (invoke {:cdt  (get-element-cdt element)
                    :cmd  "DOM.setFileInputFiles"
                    :args {:objectId (get-object-id element)
                           :files    (mapv #(.getAbsolutePath ^File %) files)}})
           (catch DevtoolsProtocolException ex
             (when (and (= -32000 (ex-code ex))
                        (= "Cannot find context with specified id" (ex-message ex)))
               (throw (cuic-ex "File input got removed during the action")))
             (throw ex))))
       element))))

;;; misc

(defn screenshot
  "Captures a screenshot from the current page and returns a byte
   array of the captured image data. Image format and quality can
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
     :or   {format  :png
            quality 50
            timeout *timeout*}}]
   (let [browser (or browser (-require-default-browser))]
     (rewrite-exceptions
       (check-arg [#{:jpeg :png} "either :jgep or :png"] [format "format"])
       (check-arg [#(and (integer? %) (<= 0 % 100)) "between 0 and 100"] [quality "quality"])
       (check-arg [nat-int? "positive integer or zero"] [timeout "timeout"])
       (check-arg [chrome? "chrome instance"] [browser "browser"])
       (let [cdt (devtools browser)
             res (invoke {:cdt     cdt
                          :cmd     "Page.captureScreenshot"
                          :args    {:format      (core/name format)
                                    :quality     quality
                                    :fromSurface true}
                          :timeout timeout})]
         (decode-base64 (:data res)))))))

(defn on-dialog
  "Registers the given handler function for JavaScript dialogs. The registered
   handler gets called every time when a new JavaScript dialog (alert, confirm,
   prompt and beforeunload confirmation) is opened to the page. Handler must
   respond by returning either `:accept` or `:dismiss`. For prompts, returned
   value can also be a string which indicates the user inputted text to the prompt.

   The handler function receives one argument which is a map describing
   the opened dialog properties:
   ```clojure
   {:message        <string>     ;; message that will be displayed by the dialog
    :type           <keyword>    ;; dialog type, one of #{:alert :confirm :prompt :beforeunload}
    :default-prompt <string>     ;; default dialog prompt
   ```

   **Important:** Handler must be registered **before** the dialog is
   opened. Registering handler unregisters the previous one automatically.
   Default handler accepts all dialogs.

   ```clojure
   ;; Accept dialog that may appear after click
   (c/on-dialog (constantly :accept))
   (c/click (c/find \"button.delete\"))

   ;; Fill username that was prompted
   (c/on-dialog (fn [{:keys [message]}]
                  (case message
                    \"Username\" \"milankinen\"
                    \"Password\" \"***\"
                    \"\")))
   (c/click (c/find \"button.delete\"))

   ;; Assert that dialog's default prompt text was previous username
   (let [default (atom nil)]
     (c/on-dialog (fn [{:keys [message default-prompt]}]
                    (reset! default default-prompt)
                    :dismiss))
     (c/click (c/find \"#change-username\"))
     (is* (= @default (c/text-content (c/find \"#username\")))))
   ```"
  ([handler] (rewrite-exceptions (on-dialog handler {})))
  ([handler opts]
   (rewrite-exceptions
     (let [browser (or (:browser opts) (-require-default-browser))]
       (check-arg [ifn? "function"] [handler "handler"])
       (check-arg [chrome? "chrome instance"] [browser "browser"])
       (let [page (get-current-page browser)
             f (fn [dialog]
                 (let [res (handler dialog)]
                   (cond
                     (= :accept res) (if (= :prompt (:type dialog))
                                       (:default-prompt dialog)
                                       true)
                     (= :dismiss res) false
                     (string? res) res
                     :else (throw (CuicException. (str "Dialog handler must return either "
                                                       ":accept, :dismiss or string if the dialog "
                                                       "is a prompt"))))))]
         (set-dialog-handler page f))
       nil))))
