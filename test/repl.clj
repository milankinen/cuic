(ns repl
  (:require [cuic.core :as c]
            [cuic.chrome :as chrome]
            [cuic.test-common :refer [forms-html-url]]))

(comment

  ;; Start local chrome for REPL usage
  (-> (chrome/launch {:headless false})
      (c/set-browser!))

  (c/goto forms-html-url)


  -)

