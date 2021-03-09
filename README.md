# <img src="kuikka.svg" align="left" width="60" height="60"> CUIC

Clojure UI testing with Chrome

[![Build status](https://img.shields.io/github/workflow/status/milankinen/cuic/Run%20tests/master?style=flat-square)](https://github.com/milankinen/cuic/actions?query=workflow%3A%22Run+tests%22)
[![Clojars Project](https://img.shields.io/clojars/v/cuic.svg?style=flat-square)](https://clojars.org/cuic)
[![cljdoc](https://img.shields.io/badge/cljdoc-latest-blue?style=flat-square)](https://cljdoc.org/d/cuic/cuic/CURRENT)

## Motivation

I needed a library for writing robust and maintainable UI tests for my work 
and hobby Clojure(Script) projects. The library had to run on top of the 
JVM to simplify test setups and enable code sharing, but without the 
annoying WebDriver version hassle. `cuic` is a response to fill that gap.

The design of the current version of `cuic` is the result of countless 
(re)written tests, hours after hours of CI test runs and enless debugging
sessions, driven by the following core principles:

  * Prefer Clojure core data structures and language features 
    to custom macros and DSLs
  * Minimal and unambiguous but easily extendable API 
  * Seamless integration with `clojure.test` and the tooling around it
  * First class REPL usage

## Show me the code!

Here's a small snippet showing how to test the classic
[TodoMVC app](http://todomvc.com/examples/react) with `cuic`

```clojure 
(ns example-todomvc-tests
  (:require [clojure.test :refer :all]
            [cuic.core :as c]
            [cuic.test :refer [deftest* is* browser-test-fixture]]))

(use-fixtures
  :once
  (browser-test-fixture))

(defn todos []
  (->> (c/query ".todo-list li")
       (map c/text-content)))

(defn add-todo [text]
  (doto (c/find ".new-todo")
    (c/fill text))
  (c/press 'Enter))

(deftest* creating-new-todos
  (c/goto "http://todomvc.com/examples/react")
  (is* (= [] (todos)))
  (add-todo "Hello world!")
  (is* (= ["Hello world!"] (todos)))
  (add-todo "Tsers!")
  (is* (= ["Hello world!" "Tsers!"] (todos))))
```

## Documentation

Each `cuic` function has a Clojure doc-string describing its behaviour and usage. 
Generated API docs and guides are also available in **[cljdoc.org](https://cljdoc.org/d/cuic/cuic)**.

* [Usage](https://cljdoc.org/d/cuic/cuic/CURRENT/doc/usage)
    * [Browser management](https://cljdoc.org/d/cuic/cuic/CURRENT/doc/usage/launching-chrome)
    * [Element queries](https://cljdoc.org/d/cuic/cuic/CURRENT/doc/usage/searching-elements)
    * [Interactions](https://cljdoc.org/d/cuic/cuic/CURRENT/doc/usage/interacting-with-page)
    * [Asynchrony](https://cljdoc.org/d/cuic/cuic/CURRENT/doc/usage/dealing-with-asynchrony)
    * [Tests and fixtures](https://cljdoc.org/d/cuic/cuic/CURRENT/doc/usage/writing-tests)
    * [REPL](https://cljdoc.org/d/cuic/cuic/CURRENT/doc/usage/repl-usage)

## Similar projects

* [Etaoin](https://github.com/igrishaev/etaoin) - Pure Clojure implementation of Webdriver protocol
* [clj-chrome-devtools](https://github.com/tatut/clj-chrome-devtools) - Clojure wrapper for Chrome Debug Protocol

## License

MIT
