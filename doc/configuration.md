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

## REPL usage

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
