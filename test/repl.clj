(ns repl
  (:require [clojure.test :refer :all]
            [cuic.core :as c]
            [cuic.dev :as dev]
            [clojure.java.io :as io])
  (:import (com.github.kklisura.cdt.launch ChromeLauncher ChromeArguments$Builder ChromeArguments)
           (com.github.kklisura.cdt.services.types ChromeTab)
           (com.github.kilianB.hashAlgorithms PerceptiveHash AverageHash DifferenceHash DifferenceHash$Precision)
           (javax.imageio ImageIO)))

(comment
  (def b (dev/launch!! 1000 1000))
  (c/close! b)
  (dev/set-browser! b)


  (def b (c/launch! {:window-size {:width 1000 :height 1000} :headless true}))
  (c/close! b)

  (dev/update-config! assoc :image-match {:hash-bits 512 :threshold 4})
  (.-tab b)

  (c/goto! "http://kela.fi")

  (defn save [i p ]
    (ImageIO/write i "PNG" (io/file p)))

  (defn load [p]
    (ImageIO/read (io/file p)))

  (def gate (first (c/q ".gate")))
  (def mask (c/q gate "img"))
  (def img (-> (first (c/q ".gate"))
               (c/screenshot {:masked-nodes mask})
               ))

  (save img "kela.png")

  (def ph (PerceptiveHash. 1024))

  (def hi (.toImage (.hash ph img) 32))


  (def mod (load "kela.png"))

  (.hammingDistance (.hash ph img) (.hash ph (load "kela.png")))
  (.hammingDistance (.hash ph img) (.hash ph (load "kela.jpg")))

  (save hi "hash.png")


  (def dims (long-array [(long (.getWidth img)) (long (.getHeight img))]))
  (seq dims)
  (def ia (IntArray. (.getData (.getDataBuffer (.getRaster img)))))
  (def i (ArrayImg. ia dims (Fraction.)))

  (def p (proxy ImgProxy))

  (.setLinkedType i (ARGBType. i))
  (.createLinkedType i)

  (io/copy (c/screenshot a {:masked-nodes m}) (io/file "kela.png"))

  (ImagePlusAdapter.  )
  (def delta (int 15))
  (def min-size 10)
  (def max-size (* 100 100))
  (def max-var 0.8)
  (def min-diversity 0.0)
  ;MserTree.buildMserTree( img, new UnsignedByteType( delta ), minSize, maxSize, maxVar, minDiversity, false )
  (def tree (MserTree/buildMserTree i (UnsignedByteType. delta) min-size max-size max-var min-diversity false))

  (cuic.impl.util/bounding-box (first (c/q "#banner")))

  (c/scroll-to! a)
  (cuic.impl.util/bounding-box a)
  (def edn)
  (get input :id)

  (c/value input)

  (c/attrs input)

  (c/goto! "file:///Users/matti/dev/repluit/examples/todomvc/index.html")

  (def todo (first (c/q "#todo-list > li")))

  (c/scroll-to! (c/q ".news-list"))

  (c/eval-in (c/q ".news-list") "window.___to_inspect = this")
  (c/eval "inspect(window.___to_inspect)")


  (def dom (.getDOM t))

  (.getAttributes dom todo)
  (-> (.getDOM t)
      )


  (->> (.getTabs cs)
       (map #(.getUrl %)))
  (.close l))




