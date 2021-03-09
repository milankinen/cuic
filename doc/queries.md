## Querying HTML elements

Queries provide access to the Chrome page and its DOM. They're the essential
part of `cuic` because every action and data access need an HTML element from 
the DOM. In `cuic`, HTML elements are represented as data structures that 
contain a handle to the page's DOM node. Cuic provides two ways of querying
elements: `cuic.core/find` and `cuic.core/query`. 

### `cuic.core/find`

`find` is the workhorse of `cuic`. The majority of UI test use cases are 
interactions with a single specific element, such as "clicking *the save button*" 
or "checking that *the popup* is open". If that element is not found, the
test (usually) can't continue and should be aborted. Hence, `find` is meant to 
provide a convenient way for finding a single element from the page and to fail
if the element is not found. It works like JavaScript's `document.querySelector`, 
taking a CSS selector and returning an HTML element that matches the selector.

```clojure 
(let [save-btn (c/find "#save-btn")]
  ;; save-btn can be used for interactions
  (is (= "Save!" (c/text-content save-btn))
  (c/click save-btn))

(let [input (c/find "input[name='bio']")]
  (c/fill input "tsers!"))

;; you can also use doto macro
(doto (c/find "input[name='bio']")
  (c/fill "tsers!"))
```

Sometimes the target element may appear in DOM after some time (e.g. after a 
successful AJAX request), thus `find` tries to wait for the selector if the 
element is not found immediately, timeouting after a certain (configurable) 
time of waiting with an exception. This ensures that if the invocation returns
an element, it is guaranteed to be found from the DOM.

```clojure 
(c/click (c/find "#save-btn"))
(is (= "Saved!" (c/text-content (c/find "#status-text"))))
```

`find` expects **exactly one** element to be found. If the selector matches 
multiple elements, `find` throws an exception. If you need to find multiple 
elements, see `cuic.core/query` description below.

### `cuic.core/query`

Whereas `find` is meant for getting single html element, `query` provides a way 
to search for multiple elements matching the certain selector. Unlike `find`, 
it **does not** wait: if there are no elements matching the given selector at the time
of the invocation, `query` returns `nil`. If there are some matching elements, those
elements are returned as a vector.

> **Pay special attention to the asynchrony** when using `query`! Because `query` does 
> not wait anything, it's extremely easy to make flaky tests by expecting something from 
> the `query` result immediately. To mitigate this, `query` usage should be combined
> with [[cuic.core/wait]] to eliminate the effects of asynchrony. Asynchrony and some
> ways of tackling it is discussed in the latter sections of this guide.

```clojure 
(defn todo-items []
  (c/query ".todo-item"))

;; The returned value is a vector so we can use any
;; clojure.core functions to manipulate it

(is (= 0 (count (todo-items))))
(is (= ["lol" "bal"]
       (->> (todo-items)
            (map c/text-content)
            (remove string/blank?))))

;; Special case, no elements found - returns nil so
;; we can use many clojure.core shorthand macros

(if-let [items (todo-items)]
  (do-something-with items)
  (println "no items found!"))
```

## Custom query predicates

So far we've gone through some basic queries with css selectors. In real cases
however, the queries might also depend on more complex information that can't
be expressed with css selector only. Your application might for example render
its form inputs using the following structure:

```clojure
[:label "First name"
 [:input]]
```

Now we want to write a utility function that finds the input by its label.
This query can't be expressed with css selector. Instead, we can pass a custom
predicate to `find` and apply necessary constraints there:

```clojure 
(defn input-by-label [label]
  (c/find {:by   "input,textarea,select"
           :when (fn [element]
                   (some-> (c/closest element "label")
                           (c/text-content)
                           (string/includes? label)))}))
```

The semantics for `find` remain same - it'll wait until the element satisfying
both selector and predicate is found, or timeouts throwing an exception. 
Elements passed to the predicate are guaranteed to match the given css selector.

Also `query` has the custom predicate support, and it works similarily to
`find`. Semantics of `query` remain same as well: if none of the found elements 
pass the predicate, `query` returns `nil`.

> **Attention!** Predicate function might get called multiple times during
> the wait period, so it's extremely important that the predicate function
> **does not** perform any side effects!

## Querying in context

Eventually there will be cases where you want to access an element that can't
be identified uniquely, for example remove the second todo item by clicking
its remove button. Writing selectors such as `.todo-item:nth-child(2) button.remove`
do not work in complex cases and lack composability, so they'll quickly become a 
maintenance hell. That's why both `find` and `query` have the concept of **context**.

So far we've been using `query` and `find` by giving only the selector as a 
parameter. Both functions, however, have an alternative invocation style that 
provides a way to define a "context" that'll be used to specify
the root element for the queries. You can think it as JavaScript's 
`element.querySelector` or `element.querySelectorAll` where matching
is done only for the nodes inside `element`'s subtree.

```clojure 
(def todo-list (c/find ".todo-list")

;; Get only todo items that are **inside** the todo-list element
(def todo-items 
  (c/query {:by ".todo-item"
            :in todo-list))

;; Get the remove button that is **inside** the second todo item
(c/find {:by "button.remove"
         :in (second todo-items)}
```

### Implicit context (experimental)

What we've found in our projects, when the application and its test cases 
become more complex, passing the context element(s) around starts to produce 
some unnecessary boilerplate and hinder the maintainability of the tests. 
Also, in some cases you might want to limit the selector into some "sub-area"
of the page whereas in some cases you might not want to do this.

To keep test code clean, `find` and `query` support also so called 
*implicit context*. The context element can be set with [[cuic.core/in]] macro 
and all code run inside the macro's body will use that element as their
root element for the queries.

Here is a (very simple) example how to use implicit context in 
the codebase:

```clojure 
;;; Without implicit context

(defn element-by-label [ctx label]
  (c/find {:in ctx :by "*" :when #(has-label? % label)}))

(defn fill-text [ctx label text]
  (c/fill (element-by-label ctx label) text))

(defn click-button [ctx label]
  (let [elem (element-by-label ctx label)]
    (assert (is-button? elem))
    (c/click elem)))

(fill-text (c/document) "Search..." "Matti"))
(c/press 'Enter)
(let [row (c/wait (first (c/query ".result-rows")))]
  (fill-text row "First name" "Matti"))
  (fill-text row "Last name" "Lankinen")
  (c/press 'Enter)
  (click-button row "Save"))
  

;;; With implicit context

(defn element-by-label [label]
  (c/find {:by "*" :when #(has-label? % label)}))

(defn fill-text [label text]
  (c/fill (element-by-label label) text))

(defn click-button [label]
  (let [elem (element-by-label label)]
    (assert (is-button? elem))
    (c/click elem)))

(fill-text "Search..." "Matti")) ;; document used by default
(c/press 'Enter)
(c/in (c/wait (first (c/query ".result-rows")))
  (fill-text "First name" "Matti"))
  (fill-text "Last name" "Lankinen")
  (c/press 'Enter)
  (click-button "Save"))
``` 

The applied context in `query` and `find` is determined using the following
priorities, the highest priority first:

  1. Explicit context set with `:in <element>` option durint the invocation 
  2. Implicit context set with `(c/in <element> ...)`
  3. Using `(c/document)` as context otherwise

> **This feature is experimental and totally optional**. In fact, Clojure best
> practices consider this kind of implicit context as an anti-pattern, so think
> carefully before applying this technique to your codebase. There are also
> other ways to structure your codebase in order to avoid unnecessary repetition.
> 
> What we've found, however, is that although this tehnique is considered as 
> an anti-pattern, it has worked pretty well in **test code** where the test 
> "steps" are synchronous and the effects indirection and laziness are isolated.
> That said, **use this tehnique with caution!**

## Naming elements

Naming elements is entirely optional, although it greatly improves the debugging
in case of failures. If, for example, the clicked element is not visible, `cuic`
throws an exception using element's selector for the error message. If selector is
complex or queried from the context, the error message might be hard to interpret 
from e.g. the CI output. 

Let's assume we have the following code. The clicked save button is disabled:

```clojure 
(defn button-by-text [text]
  (c/find {:by   "button" 
           :when #(string/includes? (c/text-content %) text)}))

(def btn (button-by-text "Save"))
(c/click btn)
;; => CuicException: Can't click element "button" because it is disabled
```

Queried elements can be named by providing the name as an option to 
the query function: `:as <name>`. Alternatively, result elements can be 
renamed with `c/as` function. The assigned custom name is retrievable 
in code with `c/name`.

```clojure 
(defn button-by-text [text]
  (c/find {:by   "button" 
           :when #(string/includes? (c/text-content %) text)
           :as   (str text " button")}))

(def btn (button-by-text "Save"))
(c/click btn)
;; => CuicException: Can't click element "Save button" because it is disabled
(c/name btn)
;; => "Save button"

(def renamed (c/as btn "Renamed button"))
(c/click renamed)
;; => CuicException: Can't click element "Renamed button" because it is disabled
(c/name renamed)
;; => "Renamed button"
```

## Closing words

Querying data and elements is an essential part of writing UI tests. 
However, tests can't simulate human actions if they can't interact with
the queried elements. The next sections of this guide go through how to
actually interact with the elements and what's the motivation behind
separating queries from interactions.
