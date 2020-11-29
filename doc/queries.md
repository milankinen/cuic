## Queries and HTML elements

Queries provide access to the Chrome page and its DOM. They're the essential
part of `cuic` because every action and data access need an HTML element from 
DOM. In `cuic`, HTML elements are represented as data structures that contain 
a handle to the actual page's DOM node. The most important queries are 
`cuic.core/find` and `cuic.core/query`. 

### `cuic.core/find`

`find` is the workhorse of `cuic`. The majority of UI test use cases are 
interactions with single element, such as "clicking the save button" or "checking 
that the popup is open" hence `find` provides a convenient way for finding single
element from the page. It works like JavaScript's `document.querySelector`, taking
a valid CSS selector and returning an HTML element that matches the selector.

```clojure 
(let [save-btn (c/find "#save-btn")]
  ;; save-btn can be used for interactions
  (is (= "Save!" (c/text-content save-btn))
  (c/click save-btn))

(let [input (c/find "input[name='bio']")]
  (c/fill input "tsers!"))

;; you can also use doto macro (or any other clojure stuff!)
(doto (c/find "input[name='bio']")
  (c/fill "tsers!"))
```

Sometimes the target element may appear in DOM after some time (e.g. after a 
successful AJAX request), thus `find` tries to wait for the selector if the 
element is not found immediately, but timeouts after a certain period. 

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
of invocation, `query` returns `nil`. If there are some matching elements, those
elements are returned as a vector.

**OBS!** Pay special attention to the asynchrony when using `query`. Because `query` 
does not wait anything, it's extremely easy to make flaky tests by expecting something
from the `query` result immediately. To mitigate this, `query` usage should be combined
with `cuic.core/wait` to eliminate the effects of asynchrony.

```clojure 
(defn todo-items []
  (c/query ".todo-item"))

(is (= 0 (count (todo-items))))
(add-todo "tsers")
;; The following form has false positive possibility because DOM might not 
;; have rendered fully at the invocation time of the assertion!
(is (= 1 (count (todo-items))))  

;;; Using wait eliminates the problem

(is (= 0 (count (todo-items))))
(add-todo "tsers")
(is (c/wait (= 1 (count (todo-items)))))  

;;;;;;;

(defn button-by-text [text]
  (->> (c/query "button")
       (filter #(string/includes? (c/text-content %) text))
       (first))

(c/fill (c/find "#bio") "tsers!")
;; Undo button may not be rendered to the dom yet!
(c/click (button-by-text "Undo"))

;;; You can also use wait in your "utility" code to prevent unnecessary repetition

(defn button-by-text [text]
  (try 
    (->> (c/query "button")
         (filter #(string/includes? (c/text-content %) text))
         (first)
         (c/wait))
    (catch Exception ex
      (if (c/timeout-ex?? ex)
        (throw (RuntimeException. (str "Could not find button by text: " text)))
        (throw ex)))))

(c/fill (c/find "#bio") "tsers!")
(c/click (button-by-text "Undo"))  ;; safe again!
```

## Querying in context

Eventually there will be cases where you want to access an element that can't
be identified uniquely, for example remove the second "todo item" by clicking
its remove button. Writing selectors such as `.todo-item:nth-child(1) button.remove`
quickly become a maintenance hell, and your tests start falling apart. Because 
this is such a common problem, `cuic` provides some built-in tools to handle it.

So far we've been using `query` and `find` by giving only the selector as a 
parameter. Both functions, however, have an alternative invocation style that 
provides a way to define a "context" that'll be used for the query. You can
think it like JavaScript `element.querySelector` or `element.querySelectorAll`
equivalent. 

```clojure 
(def todo-list (c/find ".todo-list")

;; Get only todo items that are **inside** the todo-list element
(def todo-items 
  (c/query {:by ".todo-item"
            :in todo-list))

;; Get only the remove button that is **inside** the second todo item
(c/find {:by "button.remove"
         :in (second todo-items)}
```

However, when the application and the test cases become more complex, passing 
the context element around starts to produce boilerplate and hinder the 
maintainability of your tests. 

```clojure 
(defn input-by-placeholder [ctx text]
  (try 
    (->> (c/query {:in ctx :by "input, textarea"})
         (filter #(= text (:placeholder (c/attributes %))))
         (first)
         (c/wait))
    (catch Exception ex
      (if (c/timeout? ex)
        (throw (RuntimeException. (str "Could not find input by: " text)))
        (throw ex)))))

(defn fill-text [ctx placeholder text]
  (c/fill (input-by-placeholder ctx placeholder) text))

(fill-text (c/document) "Search..." "Matti"))
(c/press 'Enter)
(let [user (c/wait (first (c/query ".users")))]
  (fill-text user "Name" "Pekka"))
  (fill-text user "Bio" "tsers!")))
``` 

To mitigate this, `cuic` provides the `in` macro which setups the default context
for `find` and `query`. This makes possible to build more modular project/domain 
specific utility functions:

```clojure 
(defn input-by-placeholder [text]
  (try 
    (->> (c/query "input, textarea")
         (filter #(= text (:placeholder (c/attributes %))))
         (first)
         (c/wait))
    (catch Exception ex
      (if (c/timeout? ex)
        (throw (RuntimeException. (str "Could not find input by: " text)))
        (throw ex)))))

(defn fill-text [placeholder text]
  (c/fill (input-by-placeholder placeholder) text))

(fill-text "Search..." "Matti"))  ;; document used by default
(c/press 'Enter)
(c/in (c/wait (first (c/query ".users")))  ;; use the first user as context inside this block
  (fill-text "Name" "Pekka"))
  (fill-text "Bio" "tsers!")))
```  

## Naming elements

Naming elements is entirely optional, although it greatly improves the debugging
in case of failures. If, for example, the clicked element is not visible, `cuic`
throws an exception using element's selector in the error message. If selector is
complex or queried from the context (see above), the error message might be hard
to interpret from e.g. CI output.  

```clojure 
(defn button-by-text [text]
  (->> (c/query "button")
       (filter #(string/includes? (c/text-content %) text))
       (first)
       (c/wait)))

;; If save button is disabled the following exception is thrown:
;; => Can't click element "button" because it is not visible
(c/click (button-by-text "Save"))

;;;;;

(defn button-by-text [text]
  (->> (c/query {:by "button" 
                 ;; Assign name to the queried elements
                 :as (str text " button")})  
       (filter #(string/includes? (c/text-content %) text))
       (first)
       (c/wait)))

;; => Can't click element "Save button" because it is not visible
(c/click (button-by-text "Save"))
```

Both `find` and `query` support `:as <name>` option. Element can also be 
renamed with `c/as` and the assigned name is retrievable in code with `c/name`.
