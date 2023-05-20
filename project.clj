(defproject cuic "1.0.0-RC2"
  :description "Concise UI testing with Clojure"
  :url "https://github.com/milankinen/cuic"
  :scm {:name "git"
        :url  "https://github.com/milankinen/cuic"}
  :license {:name "MIT"
            :url  "https://opensource.org/licenses/MIT"}
  :signing {:gpg-key "9DD8C3E9"}
  :clean-targets
  ^{:protect false}
  [:target-path
   "src/js/node_modules"
   "src/js/build"]
  :auto-clean false
  :pedantic? :abort
  :dependencies
  [[org.clojure/tools.logging "1.2.4" :exclusions [org.clojure/clojure]]
   [org.clojure/data.json "2.4.0" :exclusions [org.clojure/clojure]]
   [stylefruits/gniazdo "1.2.2" :exclusions [org.clojure/clojure]]
   [org.jsoup/jsoup "1.16.1"]]
  :source-paths ["src/clj"]
  :java-source-paths ["src/java"]
  :resource-paths ["resources" "src/js/build"]
  :profiles {:dev  {:dependencies
                    [[org.clojure/clojure "1.11.1"]
                     [http-kit "2.6.0"]
                     [compojure "1.7.0"]
                     [ch.qos.logback/logback-classic "1.4.7"]
                     [eftest "0.6.0"]
                     [clj-kondo "2023.05.18"]]
                    :managed-dependencies
                    [[com.fasterxml.jackson.core/jackson-core "2.14.2"]]
                    :repl-options
                    {:init-ns repl}
                    :plugins
                    [[lein-ancient "0.7.0"]
                     [lein-shell "0.5.0"]]}
             :repl {:jvm-opts ["-Dcuic.headless=false"
                               "-Dcuic.exceptions.full_stacktrace=true"]}}
  :deploy-repositories [["releases" :clojars]
                        ["snapshots" :clojars]]
  :aliases {"test"     ["trampoline" "run" "-m" "test-runner/run-tests-cli"]
            "build-js" ["shell" "./src/js/build.sh"]
            "t"        ["test"]
            "lint"     ["trampoline" "run" "-m" "clj-kondo.main" "--lint" "src" "test"]}
  :release-tasks [["deploy"]])
