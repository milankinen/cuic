(ns repl
  (:require [clojure.java.io :as io]
            [cuic.core :as c]
            [cuic.chrome :as chrome]))

(comment
  (def chrome (chrome/launch {:headless false}))
  (c/set-browser! chrome)
  (c/set-timeout! 2000)
  (c/goto "https://clojuredocs.org")
  (c/goto "http://is.fi")

  (def xkcd (c/find ".xkcd"))
  (def q (c/find ".query"))

  (c/hover (c/find ".xkcd"))
  (c/fill q "tsers")



  (-> (str "file://" (.getAbsolutePath (io/file "test/resources/forms.html")))
      (c/goto))



  -)

