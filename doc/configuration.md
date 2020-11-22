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
