(ns repl
  (:require [cuic.core :as c]
            [cuic.chrome :as chrome]
            [test-common :refer [forms-url todos-url]]))

(comment

  ;; Start local chrome for REPL usage
  (-> (chrome/launch {:headless false})
      (c/set-browser!))

  (c/goto forms-url)
  (c/goto todos-url)


  -)

