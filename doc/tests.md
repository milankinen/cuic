## Test suite configuration

The primary purpose of `cuic` is to provide a minimalistic interface for 
writing UI tests and leveraging idiomatic `clojure.test` convetions as much
as possible. The configuration is done using dynamic variables which pair 
nicely with `use-fixtures`. All (or at least majority of) project specific 
configurations are supposed to be put inside a fixture using `binding` blocks 
and shared across the test namespaces using this fixture. For some cases
these configurations may be overrided per invocation if necessary.

Here is an example of a simple UI test fixture and how to use it
in test cases.

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
the browser is explictly defined per invocation). The value must be a valid 
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
that queries and actions use when they wait for the page to be ready to satisfy
their requirements (e.g. element appears in dom or becomes visible and enabled
for clicking). Generally you want to configure this variable to be as small
as you can in your local development environment but because CI machines 
tend to be slower, increasing the value to make tests more robust against 
network or CPU hiccups and asynchronous UI interactions (such as animations).

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
can use QA environment or any other host that is accessible from the test 
runner machine.

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
of the scope of this guide. If your requirements are simple, you might also
be able to use `cuic.test/browser-test-fixture` which handle these configurations
automatically for you. 

Note also that if you're not running your tests in parallel, you can share 
the Chrome instance across tests runs by launching it before the startup of
the test suite and closing it after the suite is finished (consult your test 
runner's documentation for finding proper hooks for this). This may significantly 
imporove your test suite run time because lauching single Chrome instance 
may take up-to few seconds per launch. Using `:once` instead of `:each` for 
your `ui-test-fixture` also helps with the startup delay.

> It's generally not advisable to run UI tests in parallel because they
> require a lot of resources and running them in parallal may slow their
> execution (especially in CI machines), resulting in false positive
> timeouts and flaky tests.

## `deftest*` and `is*`

### Reducing assertion boilerplate with `is*`

When testing UIs, practically every step should be considered 
asynchronous. This means that every assertion must be waited. 
Writing `(is (c/wait ...))` everywhere is a tedious task and gets 
forgotten easily. In addition, if you're using a custom test runner
that pretty prints failed assertions and their diffs (e.g. `eftest` 
or Cursive's test runner), `c/wait` will mess up the pretty printing. 

To avoid this, `cuic` provides `cuic.test/is*` which is a shorthand 
for `(is (c/wait ...))` with some test reporter magic to preserve 
pretty printing. It also tries to capture screenshot from the active
browser page in case of assertion failure. Screenshots will be saved
under `target/screenshots` by default, but the location can be changed
by overriding the [[cuic.test/*screenshot-options*]] configration
variable (`:dir` option).

The usage of `is*` does not differ from traditional `is` usage at all:

```clojure 
(is (c/wait (c/visible? (save-button))))
(is (c/wait (c/has-class? (save-button) "primary"))) 
(is (c/wait (re-find #"Changes saved" (c/inner-text (save-summary)))))

;;;; => 
(require '[cuic.test :refer [is*])

(is* (c/visible? (save-button)))
(is* (c/has-class? (save-button) "primary"))
(is* (re-find #"Changes saved" (c/inner-text (save-summary))))
```

Like `wait`, also `is*` can be used for non-UI assertions:

```clojure 
(add-todo "Foo")
(add-todo "Bar")
(click-btn "Save")
(is* (= #{"Foo" "Bar"} (get-todos-from-db))))
```

### Graceful test aborts with `deftest*`

Because of the nature of UI tests, first assertion failure will usually 
indicate the failure of the remaining assertions as well. If each of 
these assertions wait the timeout before giving up, the test run might 
prolong quite a bit. That's why `is*` aborts the current test run 
immediately after the first failed assertion (this behaviour can be 
changed with [[cuic.test/*abort-immediately*]] configuration variable).

Aborting is implemented by throwing an `AbortTestError` from the failed
assertion. If you're using `deftest`, the test suite catches and reports 
this error as well. If you want to avoid this unnecessary "double failure",
you can switch the `clojure.test/deftest` to `cuic.test/deftest*` which
will suppress the abort error gracefully and tries to capture screenshot
if it encounters any other unexpected errors. It also provides more information 
to `is*` assertions about the executed test, resulting in more descriptive 
screenshot filenames.

## Closing words

Although it's crucial to be able to make tests deterministic and maintanable, 
the importance of the ease of creating and debugging them can't be underrated. 
In the last section of this guide, you'll find how to integrate `cuic` as a
natural part of your development (REPL) workflow.
