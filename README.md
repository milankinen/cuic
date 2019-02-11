# <img src="kuikka.png" align="left" width="60" height="60"> CUIC - Concise UI testing with Clojure

> Use the REPL, Luke!

[![Build Status](https://img.shields.io/travis/milankinen/cuic/master.svg?style=flat-square)](https://travis-ci.org/milankinen/cuic)
[![Clojars Project](https://img.shields.io/clojars/v/cuic.svg?style=flat-square)](https://clojars.org/cuic)

## Quick start

```clj
; listing all stargazers for fun
; eval these forms in your REPL
(ns quickstart)

(require '[cuic.core :as c])
(require 'cuic.repl)

(cuic.repl/launch-as-default!)
(c/goto! "https://github.com/milankinen/cuic")
(let [btn (->> (c/q ".pagehead-actions li a")
               (filter #(re-find #"/stargazers$" (or (:href (c/attrs %)) "")))
               (seq)
               (c/wait))]
  (c/click! btn))

(c/wait (= "Stargazers" (c/inner-text (c/q "#repos h2"))))
(println "Yay! Stargazers are:")
(->> (c/q ".follow-list-name a")
     (mapv c/inner-text)
     (clojure.pprint/pprint))

```

## Goal of this project

At one day, one of my colleague came to me asking: _"Hey! Do you have any hints how
to develop our UI tests with the REPL like we do with all other code?"_ No. I didn't have
any. However, I started to think: why does it have to be like that? Why one couldn't
just start a REPL, and start developing the UI tests, just like any other code?

The goal of this library is to enable a seamless REPL workflow for your UI tests so that
you can develop and maintain them with smallest possible feedback loop: read-eval-print.
Start a browser with one form, select elements with another and make the browser to click
them with a third one. After you're satisfied with the result, wrap the forms
into a `defn` and reuse it in other tests!

## Design principles

- Embrace REPL usage - all functionality must be easily tested and accessible from REPL
  but also scoped by default so that they can be used as a part of actual test cases

- Respect native data structures and function composition - CUIC query selectors return
  a vector of DOM nodes that can be further processed and queried with other query functions:

  ```clj
  ; visible, enabled input elements under #form
  (->> (c/q "#form input")
       (filter c/visible?)
       (remove c/disabled?))

  ; text contents of all p elements under #content, concatenated with newline
  (->> (c/q "#content p")
       (map c/text-content)
       (string/join "\n"))
  ```

- No built-in DSLs. Each application and its test requirments are unique so testing library
  should just provide a set of modular basic blocks that can be used to model application's
  domain as fit best. In CUIC this means that there are no constructs like "pages" or "forms"
  or anything like that - it just have a bunch of query functions and mutation that can be
  combined to model the requirements.

## Usage

CUIC consists of three "logical" parts: (1) **general setup**, (2) **query functions** and
(3) **mutations**. The description of each part is below. For a complete reference, see
**[API docs](https://cljdoc.org/d/cuic/cuic)**.

### General setup

CUIC browser can be launched by using `cuic.core/launch!`. Launched browser instance
implements `java.io.Closeable` so it can be used with `with-open`. After the browser has
been launched, it should be set as default browser. This can be done using `binding`.

CUIC has also some configuration options that can be (optinally) overrided by using the
same `binding`. Note that these configurations are overrideable at any point during
the tests. Normal `binding` rules apply here.

Here is an example of the test setup with `clojure.test` and `use-fixtures`:

```clj
(defn browser-test-fixture [test]
  (with-open [test-browser (c/launch! {:headless true})]
    (binding [c/*browser* b
              c/*config*  (assoc c/*config* :typing-speed :tycitys)]
      (test))))

(use-fixtures :once browser-test-fixture)
```

### Query functions

Query functions are normal Clojure functions that communicate with the current
browser (see `cuic.core/*browser*`) and return the received values to the caller.
Return values are synchronous clojure primtive data structures that can be manipulated
with standard library functions like any other clojure data structure. Please see
**[API docs](https://cljdoc.org/d/cuic/cuic)** for a complete reference of available
query functions.

Example:

```clj
; return all values of enabled inputs having class "invalid"
(->> (c/q "#form input")
     (remove c/disabled?)
     (filter #(c/has-class? % "invalid")
     (map c/value)))
```

### Mutations

Mutations are actions that manipulate the current web page in a some way. If they require
a DOM node in order to execute, they are able to wait until that DOM node is present (and
visible) in the DOM before they continue their execution. Please see
**[API docs](https://cljdoc.org/d/cuic/cuic)** for a complete reference of available
mutations.

Example:

```clj
; click submit button
(c/click! (c/q "#form button.submit"))
```

#### Why are mutations macros?

Astute developer might notice that CUIC core mutations are implemented as macros. Usually the
best way to use macros is not using them at all. Why not to use normal functions instead?

In mutations' case, macros have two advantages: they allow re-executing the forms multiple
time, making possible to "poll" until DOM is ready before proceeding. This allows to have
query functions returning Clojure data structures and still preserve ability to
[deal with asynchronicity](#dealing-with-asynchronicity) in a concise way. Macros also
enable more declarative erro messages in case of failures.

In fact, mutations were functions in previous iterations and there was a `c/with-retry`
macro to handle asynchronicity. This however turned out to be too hard to understand and
use.

## REPL

Because each query function and mutation requires a browser bound to `cuic.core/*browser*`
with the use of `binding`, using them with REPL would be too cumbersome. That's why CUIC provides `cuic.repl` namespace. It contains some utility functions that allows you to launch
a browser with sensible defaults and attach it as a default browser so that mutations and
query functions work out-of-box. Please see **[API docs](https://cljdoc.org/d/cuic/cuic)** for a complete reference of available REPL utilities.

Example:

```clj
; launch browser and attach it as default browser
(require 'cuic.repl)
(cuic.repl/launch-as-default!)
; ... and now you can use any mutation and query function without
; worrying about `binding`
(c/click! (c/q "#my-button"))
```

## Dealing with asynchronicity

TODO

## Cook book

TODO

##

## License

MIT
