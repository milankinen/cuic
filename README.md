# <img src="kuikka.svg" align="left" width="60" height="60"> CUIC

Concise UI testing with Clojure

[![Build Status](https://img.shields.io/travis/milankinen/cuic/master.svg?style=flat-square)](https://travis-ci.org/milankinen/cuic)
[![Clojars Project](https://img.shields.io/clojars/v/cuic.svg?style=flat-square)](https://clojars.org/cuic)

* CI-ready - works on top of [Chrome DevTools Protocol](https://chromedevtools.github.io/devtools-protocol) and
supports headless execution out-of-the-box
* No DSLs or implicit waits, just data and functions to manipulate that data
* Designed to support the idiomatic REPL workflow
* Seamless Cursive integration (see [demo video](#TODO))

## Quick start

```clj
; Evaluate these forms in your REPL, form by form
(ns demo)

(require '[cuic.core :as c])
(require 'cuic.repl)

(cuic.repl/launch-dev-browser! {:window-size {:width 1500 :height 1000}})
(c/goto! "https://clojuredocs.org")

(def core-link
  (->> (c/q ".navbar li a")
       (filter c/visible?)
       (filter #(= "Core Library" (c/text-content %)))
       (first)
       (c/wait)))
(c/click! core-link)

(def test-lib-link
  (->> (c/q ".library-nav li a")
       (filter #(= "test" (c/text-content %)))
       (first)
       (c/wait)))
(c/click! test-lib-link)

(def test-var-names
  (->> (c/wait (c/q ".var-group"))
       (mapcat #(c/q % ".name"))
       (map c/inner-text)
       (sort)))

(do (println "Vars in clojure.test are:")
    (doseq [var-name test-var-names]
      (println " " var-name)))
``` 

## Usage

`WIP...` See API docs **[here](https://cljdoc.org/d/cuic/cuic)**.


## License

MIT
