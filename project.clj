(defproject cuic "0.0.1"
  :description "Concise UI testing with Clojure"
  :url "https://github.com/milankinen/cuic"
  :license {:name "MIT"
            :url  "https://opensource.org/licenses/MIT"}
  :dependencies [[clj-chrome-devtools "20180528"]
                 [org.clojure/tools.logging "0.4.1"]
                 [commons-io/commons-io "2.6"]
                 [org.jsoup/jsoup "1.11.3"]]
  :plugins [[lein-ancient "0.6.15"]]
  :source-paths ["src/clj"]
  :java-source-paths ["src/java"]
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.9.0"]
                                  [ch.qos.logback/logback-classic "1.2.3"]]}}
  :deploy-repositories [["releases" :clojars]]
  :aliases {"t" "test"}
  :release-tasks [["deploy"]])
