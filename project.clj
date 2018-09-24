(defproject repluit "0.0.1"
  :description "REPL powered UI Testing"
  :url "https://github.com/milankinen/repluit"
  :license {:name "MIT"
            :url  "https://opensource.org/licenses/MIT"}
  :dependencies []
  :plugins [[lein-ancient "0.6.15"]]
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.9.0"]]}}
  :deploy-repositories [["releases" :clojars]]
  :aliases {"t" "test"}
  :release-tasks [["deploy"]])
