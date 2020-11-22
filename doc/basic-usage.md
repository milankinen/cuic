## Overview

The main functionality of `cuic` can be found from the `cuic.core` namespace,
which contains functions for page queries, inspection and interactions. These
functions can conceptually be divided into three categories: **queries**, 
**property getters** and **actions**. 

* Queries (e.g. `find`) can be used to find dom node(s) from the page
* Property getters (e.g. `attributes`) can be used to inspect properties
  from the queried nodes
* Actions (e.g. `click`) can be used to interact with the queried nodes

In addition to these, `cuic` has `cuic.test` namespace which provides some 
convenience utilities for tests assertions and `cuic.chrome` namespace for 
Chrome instance launching and management.

## Configuration

The primary purpose of `cuic` is to provide a minimalistic interface for 
writing UI tests and leveraging idiomatic `clojure.test` convetions as much
as possible. The configuration is done using dynamic variables which pair 
nicely with `use-fixtures`. All (or at least majority of) project specific 
configurations are supposed to be put inside a fixture using `binding` blocks 
and shared across the test namespaces using this fixture. For some cases
these configurations may be overrided per invocation if necessary.

```clojure 
(ns my.project.common)
(defn ui-test-fixture [t]
  (binding [...] ;; configs here
    (t)))


(ns my.project.foo-tests
  (:require [clojure.test :refer :all]
            [cuic.core :as c]
            [my.project.common :refer [ui-test-fixture]]))

(use-fixtures :each ui-test-fixture)

(deftest my-foo-test 
  (c/goto (get-my-server-url))
  (c/click (c/find "#my-btn"))
  ...)
```

### Configuration variables

The most important configuration variables are `cuic.core/*browser*`,
`cuic.core/*timeout*` and `cuic.core/*base-url*`. There are also some 
other configuration variables, and their usage can be found from 
the API docs.

#### `cuic.core/*browser*`

This configuration variable defines the default browser that will be used 
in `cuic.core` queries: they can't be used without setting this value (unless 
browser is explictly defined per invocation). The value must be a valid 
Chrome instance that can be obtained from `cuic.chrome/launch`. Chrome instances
implement `java.lang.AutoCloseable`, meaning that they are compatibile with
Clojure's `with-open` macro. The most usual pattern is to combine `with-open`
and `binding` blocks in your fixture:

```clojure 
(ns my.project.common
  (:require [cuic.core :as c]
            [cuic.chrome :as chrome]))

(defn ui-test-fixture [t]
  (with-open [chrome (chrome/launch)]
    (binding [c/*browser* chrome]
      (t))))
```

#### `cuic.core/*timeout*`

This configuration variable controls the timeout value (in milliseconds) 
that queries and actions use when they wait for dom to be ready to satisfy
their requirements (e.g. node appears in dom or becomes visible and enabled
for clicking). Generally you want to configure this variable to be as small
as you can in your local development environment but because CI machines 
tend to be slower, increasing the value to make tests more robust against 
network / CPU hiccups and asynchronous UI interactions (such as animations).

```clojure 
(ns my.project.common
  (:require [cuic.core :as c]
            [cuic.chrome :as chrome]))

(defn ui-test-fixture [t]
  (binding [c/*timeout* (if (is-local-env?) 2000 20000)]
    (t)))
```

#### `cuic.core/*base-url*`

This configuration variable is optional but very useful. It defines the
base url that will be used in `cuic.core/goto` if no host is given. This
allows you to decouple your test case from the used server instance: it can
be for example spinned locally to a random port per each test case, or it
can use QA environment or any other host accessible from the test machine.

```clojure 
(ns my.project.common
  (:require [cuic.core :as c]
            [cuic.chrome :as chrome]))

(defn ui-test-fixture [t]
  (binding [c/*base-url* "http://qa.my-app.com"]
    (t)))

...

(deftest login-test
  (c/goto "/login")    ;; will use http://qa.my-app.com/login as browser address
  ...)
```

This allows you to for example start new server instance per test 
without the test actually knowning about the server hostname or port.  

#### Putting it all together

After setting the browser and timeout (and optionally server's base url), 
your `ui-test-fixture` might look somehow like this:

```clojure 
(ns my.project.common
  (:require [cuic.core :as c]
            [cuic.chrome :as chrome]))

(defn ui-test-fixture [t]
  (with-open [chrome (chrome/launch)
              server (launch-my-app-server)]
    (binding [c/*browser*  chrome
              c/*timeout*  (if (is-local-env?) 5000 20000)
              c/*base-url* (str "http://localhost:" (:port server))]
      (t))))
```

Propably your test requirements are more complex, and the fixture might need
to set up other things as well (such as server or database) but they are out
of the scope of this article. If your requirements are simple, you might also
be able to use `cuic.test/browser-test-fixture` which handle these configurations
automatically for you. 

Note also that if you're not running your tests in parallel, you can share 
the Chrome instance across tests runs by launching it before the startup of
the test suite and closing it after the suite is finished (consult your test 
runner's documentation for finding proper hooks for this). This may significantly 
imporove your test suite run time because lauching single Chrome instance 
may take up-to few seconds per launch. At least use `:once` instead of
`:each` for your `ui-test-fixture` whenever possible.

> It's generally not advisable to run UI tests in parallel because they
> require a lot of resources and running them in parallal may slow their
> execution (especially in CI machines), resulting in false positive
> timeouts and flaky tests.

### REPL usage

REPL is one of the most power tools that separates Clojure (or Lisps in general)
from other languages. That's why the REPL usage has been on the top of the
mind when designing `cuic`'s API. There will be a lot of cases where you 
can benefit from proper REPL usage, for example when building a new test 
from scratch or when debugging some failed assertion. In such cases, evaluation
of single test forms is crucial. However, `binding` based approach does not 
work at all if you want to evaluate just one form *inside* from a `binding` 
block. 

In order to make REPL usage as fluent as possible, `cuic` provides `set-<config>!` 
functions for each configration variables that can be used to override 
variable's default value **globally**. First you need roughly the following 
function that you can evaluate in REPL before you start to use `cuic`:

```clojure 
(ns my.project.repl
  (:require [cuic.core :as c]
            [cuic.chrome :as chrome]))

(defn setup-env! []
  (c/set-browser! (chrome/launch {:headless false}))
  (c/set-timeout! 1000)
  (c/set-base-url! (get-development-server-url-somehow))
  ;; set some other project specific variables if needed
  ...)
```

After you've evaluated the `(my.project.repl/setup-env!)`, you should have a new 
non-headless chrome opened and ready for use. Then you can start "replaying" your 
test, form-by-form:

```clojure 
(ns my.project.user-tests
  (:require [clojure.test :refer :all]
            [cuic.core :as c]
            [my.project.common :refer [ui-test-fixture]))

(use-fixtures :once ui-test-fixture)
(use-fixtures :each (fn [t]
                      (c/goto "/users"))   ;; <- evaluate this first to go to the proper page

(deftest user-seach-test 
  (c/fill (c/find ".search") "matti")      ;; now you can evaluate these forms in your repl
  (c/press 'Enter)                         ;; one by one and see how they affect the browser
  (-> (c/query ".result-row")
      (first)
      (c/wait)
      (c/click))
  (is (= "tsers." (c/text-content (c/find "#user-bio"))))
  ...)
``` 

Another cool thing is that **you can interact with the launched Chrome manually 
as well**: click, type, inspect it with devtools etc. and all the changes you 
make can also be queried from REPL (for example if you change `#user-bio` value, 
the next evaluation of `(c/text-content (c/find "#user-bio"))` will return the 
modifed value)!

## Queries and DOM nodes

Queries provide access to the Chrome page and its DOM nodes. They're the essential
part of `cuic` because every action or check needs a DOM node instance. DOM 
nodes are data structures that contain a handle to the actual page's DOM node.
The most important queries are `cuic.core/find` and `cuic.core/query`. 

### `cuic.core/find`

`find` is the workhorse of `cuic`. The majority of UI test use cases are 
interactions with single node, such as "clicking the save button" or "checking 
that the popup is open" hence `find` provides a convenient way for finding single
node from the page. It works like JavaScript's `document.querySelector`, taking
a valid CSS selector and returning a DOM node that matches the selector.

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

Sometimes the target node may appear in DOM after some time (e.g. after a successful
AJAX request), thus `find` tries to wait for the selector if the node is not present 
immediately, but timeouts after a certain period (see `cuic.core/*timeout*`). 

```clojure 
(c/click (c/find "#save-btn"))
(is (= "Saved!" (c/text-content (c/find "#status-text"))))
```

`find` expects **exactly one** node to be found. If the selector matches multiple
nodes, `find` throws an exception. If you need to find multiple nodes, see 
`cuic.core/query` description below.

### `cuic.core/query`

Whereas `find` is meant for getting single node, `query` provides a way to search 
for multiple nodes matching the certain selector. Unlike `find`, it **does not** 
wait for anything: if there are no nodes matching the given selector at the time
of invocation, `query` returns `nil`. If there are some matching nodes, a vector of
those nodes is returned.

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

### Querying in context

Eventually there will be cases where you want to access a DOM node that can't
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
the context node around starts to produce boilerplate and hinder the 
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

### Naming nodes

Naming nodes is entirely optional, although it greatly improves the debugging
in case of failures. If, for example, the clicked node is not visible, `cuic`
throws an exception using node's selector in the error message. If selector is
complex or queried from the context (see above), the error message might be hard
to interpret from e.g. CI output.  

```clojure 
(defn button-by-text [text]
  (->> (c/query "button")
       (filter #(string/includes? (c/text-content %) text))
       (first)
       (c/wait)))

;; If save button is disabled the following exception is thrown:
;; => Can't click node "button" because node is not visible
(c/click (button-by-text "Save"))

;;;;;

(defn button-by-text [text]
  (->> (c/query {:by "button" 
                 ;; Assign name to the queried nodes
                 :as (str text " button")})  
       (filter #(string/includes? (c/text-content %) text))
       (first)
       (c/wait)))

;; => Can't click node "Save button" because node is not visible
(c/click (button-by-text "Save"))
```

Both `find` and `query` support `:as <name>` option. Node can also be renamed
with `c/as` and the assigned name is retrievable in code with `c/name`.

## Property getters

TODO

## Actions 

TODO

