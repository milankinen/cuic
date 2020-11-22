## Interacting with DOM

After you've obtained a DOM node by using a [query](./queries.md), you can 
interact with it: read its properties (e.g. classes or attributes) or perform 
some actions to it (e.g. click it). `cuic` doesn't provide any ready-made 
DSLs like `click("#my-button").having({text: "Save"}).and.then.is({visible: true})`.
Instead, `cuic` encourages you to create your own domain-specific one 
by using the supplied core building blocks and Clojure language primitives.

### Reading DOM properties

`cuic` provides various functions for DOM data reading. In order to use 
these fucntions you need a handle to an existing DOM node by using either 
`find` or `query`:

```clojure 
;; Check whether the save button has "primary" class or not
(c/has-class? (c/find "#save-btn") "primary")

;;;;;

;; Function that returns a button by the given text
(defn button-by-text [text]
  (->> (c/query "button")
       (filter #(string/includes? (c/text-content %) text))
       (first)
       (c/wait))) 

;;;;;

;; Function that returns handle to added todo items
(defn todos []
  (c/query ".todo-item"))

;; Return text from todo item
(defn todo-text [todo]
  (-> (c/find {:in todo :by "label"})
      (c/inner-text)
      (string/trim)))

;; Now you can compose these functions to perform the actual test
;; actions and assertions
(add-todo "foo")
(add-todo "bal")
(edit-text (first (todos)) "lol")
(is (= ["lol "bal] (map todo-text (todos))))
```

Function calls do not perform any implicit waiting. Instead, they 
return the state as it is during the invocation time. Note that due 
to the mutable nature of DOM, read functions are **not** referentially 
transparent. In other words, subsequent calls may return different 
results if the DOM changes between the calls. You must take this 
into account when implementing your functions and assertions: use 
`cuic.core/wait` to eliminate the issues with asynchrony when you're 
expecting something to be found. Read functions do not perform any 
mutations to the DOM so its  safe to call them multiple times. However, 
pay attention that the referenced DOM node does not get removed from 
the DOM; in such case `cuic` will throw an exception.

To see the complete list of read functions, see `cuic.core` reference
from the [API docs](https://cljdoc.org/d/cuic/cuic). 

### Performing actions

Actions simulate the user behaviour on the page: clicks, typing, focusing
on nodes, scrolling, etc... Like reading function, actions require a 
handle to the target DOM node. However, unlike reading functions, actions 
may implicitly wait until the target node condition enables the specific 
action: for example `(c/click save-btn)` will wait until the `save-btn` 
becomes visible and enabled. If the required condition is not satisfied
within the defined timeout (see [configuration](./configuration.md) for
more details), action fails with an exception.

**OBS!** Because actions may actually *mutate* the page state, be 
careful to call them only once. In other words, do **not** place any
actions inside `cuic.core/wait` or results may be devastating. 

All built-in node actions take the target node as a first argument, so 
they work well with Clojure's built-in `doto` macro. 

```clojure 
(defn add-todo [text]
  (doto (c/find ".new-todo)
    (c/clear-text)
    (c/fill text))
  (c/press 'Enter))
```

To see the complete list of available actions, see `cuic.core` reference
from the [API docs](https://cljdoc.org/d/cuic/cuic). 

### JavaScript evaluation

Sometimes built-in functions don't provide any sensible means for getting
the required information from the page. In such cases, the information may
be available via direct JavaScript evaluation. That's why `cuic` has
`eval-js` and `exec-js`. They provide a way to evaluate plain JavaScript 
expressions on the page and get results back as Clojure data structures.

`eval-js` expects an *expression* and returns the result of that expression.
Expression may be parametrized but both the parameters and the return value
must be serializable JSON values. The evaluated expression may, however,
have `this` binding rebound to a queried DOM node, allowing access to the 
node's properties. By default, `this` is bound to `c/window` object.

```clojure 
(defn title-parts [separator]
  {:pre [(string? separator)]}
  (c/eval-js "document.title.split(sep)" {:sep separator}))

;; Returns boolean whether the given checkbox has indeterminate state or not
(defn indeterminate? [checkbox]
  (c/eval-js "this.indeterminate === true" {} checkbox))
```

`exec-js` is the mutating counterpart of `eval-js`. Instead of an expression,
`exec-js` takes an entire function body. It does not return anything unless 
explicitly defined with `return <expr>;` JS statement at the end of the function
body. Arguments and `this` binding work similarily with `exec-js` and `eval-js`.

```clojure 
(defn set-title [new-title]
  (c/exec-js "const prev = document.title;
              document.title = val;
              return prev;" {:val new-title})) 
```  

Both `eval-js` and `exec-js` support asynchronous values out-of-box. If your
expression or statement is asynchronous, you can wait for it with JavaScript's
`await` keyword. Clojure code will wait until the async result is available
and then return it to the caller. 

```clojure 
;; will yield true after 500 ms
(is (= "tsers" (c/eval-js "await new Promise(resolve => setTimeout(() => resolve('tsers'), 500))")))
```

