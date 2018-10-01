(defproject cuic "0.0.1"
  :description "Concise UI testing with Clojure"
  :url "https://github.com/milankinen/cuic"
  :license {:name "MIT"
            :url  "https://opensource.org/licenses/MIT"}
  :repositories [["JCenter" "https://jcenter.bintray.com/"]]
  :dependencies [[org.clojure/tools.logging "0.4.1"]
                 [org.clojure/data.json "0.2.6"]
                 [com.github.kklisura.cdt/cdt-java-client "1.3.1"]
                 [org.jsoup/jsoup "1.11.3"]
                 [com.github.kilianB/JImageHash "1.0.1"]]
  :plugins [[lein-ancient "0.6.15"]]
  :source-paths ["src/clj"]
  :java-source-paths ["src/java"]
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.9.0"]]}}
  :deploy-repositories [["releases" :clojars]]
  :aliases {"t" "test"}
  :release-tasks [["deploy"]])
