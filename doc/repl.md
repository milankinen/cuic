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
