(defproject cuic-examples "0.1.0-SNAPSHOT"
  :description "cuic examples"
  :url "https://github.com/milankinen/cuic"
  :license {:name "MIT"
            :url  "https://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [cuic "1.0.0-RC1"]
                 [ch.qos.logback/logback-classic "1.2.3"]]
  :repl-options {:init-ns examples.core}
  :profiles {:repl {:jvm-opts ["-Dcuic.headless=false"]}})
