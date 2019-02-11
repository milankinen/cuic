# <img src="kuikka.png" align="left" width="60" height="60"> CUIC - Concise UI testing with Clojure

> Use the REPL, Luke!

[![Build Status](https://img.shields.io/travis/milankinen/cuic/master.svg?style=flat-square)](https://travis-ci.org/milankinen/cuic)
[![Clojars Project](https://img.shields.io/clojars/v/cuic.svg?style=flat-square)](https://clojars.org/cuic)

## Quick start

```clj
(ns cuic.quick-start)
; eval these forms in your REPL
(require '[cuic.core :as c])
(require '[cuic.test :refer [is*]])
(require 'cuic.dev)

(cuic.dev/launch-as-default!)
(c/goto! "https://github.com/")
(c/type! (c/q ".header-search-input") "milankinen/cuic" :enter)
(let [link (c/wait (->> (c/q ".codesearch-results .repo-list-item a")
                        (filter (comp #{"milankinen/cuic"} c/text-content))
                        (first)))]
  (c/click! link))
(is* (= "CUIC - Concise UI testing with Clojure" (c/text-content (c/q "#readme h1"))))
```

## Goal of this project

At one day, one of my colleague came to me asking: _"Matti, do you have any tips how
to develop our UI tests with the REPL like we do with all other code?"_ No. I didn't have
any. However, I started to think: why does it have to be like that? Why one couldn't
just start a REPL, and start developing the UI tests, just like any other code?

The goal of this library is to enable a seamless REPL workflow for your UI tests so that
you can develop and maintain them with smallest possible feedback loop: read-eval-print.
Start a browser with one form, select elements with another and make the browser to click
them with a third one. After you're satisfied with the result, wrap the forms
into a `defn` and reuse it in other tests!

## Design principles

- Honor native data structures and function composition - CUIC query selectors return
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

- Avoid DSLs. Asynchronous nature of UI testing and all possible race conditions are
  hard to understand, even without any extra obfuscating DSL.

## Usage

For a complete reference, see **[API docs](https://cljdoc.org/d/cuic/cuic)**.

TODO

## License

MIT
