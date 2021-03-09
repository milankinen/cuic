## REPL usage

REPL is one of the most power tools that separates Clojure (or Lisps in general)
from other languages. That's why the REPL usage has been on the top of the
mind when designing `cuic`'s API. 

`binding` based approach works well with test fixtures but has a horrible 
REPL experience. In order to make REPL usage as fluent as possible, `cuic` 
provides `set-<config>!` functions for each configration variables that can 
be used to override variable's default value **globally**. By using these,
you can set up your REPL environment (for example during the startup) in such
state that you can evaluate individual forms easily.

The actual setup implementation may vary a lot between projects, but the 
following skeleton has turned out the most convenient so far:

```clojure 
(ns my.project.repl
  "Init namespace for REPL"
  (:require [cuic.core :as c]
            [cuic.chrome :as chrome]))

;; Starts the local app when this namespace gets evaluated,
;; during the REPL server boostrap
(defonce app (start-app!))
  
(defn setup-test-env! []
  (c/set-browser! (chrome/launch {:headless false}))
  (c/set-timeout! 1000)
  (c/set-base-url! (get-app-url app))
  ;; set some other project specific variables if needed
  ...)
```

In the above setup, `my.project.repl` is defined as REPL's `:init-ns`,
so it gets evaluated automatically when the REPL server starts. This
also starts a local app instance to `http(s)://localhost:<port>`.

Now when you want to start working on the tests, you can evaluate
`(my.project.repl/setup-test-env!)`, which launches a new local Chrome
process, and configures `cuic` to use the running local app url as
the base url. Once this is done, you can open your test code and
start evaluating individual forms, one by one. Also notice that since 
the Chrome is running in non-headless mode, you can interact the page 
also through the browser (click, type, inspect it with devtools etc.) 
in addition to the programmatical access. 

## Closing words

This section ends the usage guide. Thanks for reading this far and
happy testing!
