(ns repl
  (:require [cuic.core :as c]
            [cuic.chrome :as chrome]
            [test-common :refer [forms-url todos-url]]))

(comment

  ;; Start local chrome for REPL usage
  (-> (chrome/launch {:headless false})
      (c/set-browser!))


  (require '[clojure.string :as string])

  (defn button-by-text [text]
    (->> (c/query {:by "a" :as (str text " button")})
         (filter #(string/includes? (c/text-content %) text))
         (first)
         (c/wait)))

  (c/click (button-by-text "Active"))
  (c/click (button-by-text "All"))

  (c/goto forms-url)
  (c/goto todos-url)


  -)

