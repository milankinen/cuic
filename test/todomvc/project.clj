(defproject todomvc-reagent "0.6.0"
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/clojurescript "1.10.339"]
                 [reagent "0.8.1"]
                 [figwheel-sidecar "0.5.16"]]

  :plugins [[lein-cljsbuild "1.1.7"]
            [lein-figwheel "0.5.16"]
            [lein-ancient "0.6.15"]]

  :hooks [leiningen.cljsbuild]

  :profiles {}

  :figwheel {:repl false
             :css-dirs ["."]}

  :cljsbuild {:builds [{:id           "dev"
                        :source-paths ["src"]
                        :figwheel     {:on-jsload "todomvc.core/run"}
                        :compiler     {:main                 todomvc.core
                                       :optimizations        :none
                                       :output-to            "resources/public/js/app.js"
                                       :output-dir           "resources/public/js/dev"
                                       :asset-path           "resources/public/js/dev"
                                       :source-map-timestamp true}}
                       {:id           "min"
                        :source-paths ["src"]
                        :compiler     {:main            todomvc.core
                                       :optimizations   :advanced
                                       :output-to       "resources/public/js/app.js"
                                       :output-dir      "resources/public/js/min"
                                       :elide-asserts   true
                                       :closure-defines {goog.DEBUG false}
                                       :pretty-print    false}}]})
