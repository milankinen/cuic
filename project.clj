(defproject cuic "0.6.0-20201130.1"
  :description "Concise UI testing with Clojure"
  :url "https://github.com/milankinen/cuic"
  :scm {:name "git"
        :url  "https://github.com/milankinen/cuic"}
  :license {:name "MIT"
            :url  "https://opensource.org/licenses/MIT"}
  :signing {:gpg-key "9DD8C3E9"}
  :clean-targets ^{:protect false} [:target-path "src/js/node_modules" "src/js/build"]
  :auto-clean false
  :dependencies
  [[org.clojure/tools.logging "1.1.0"]
   [org.clojure/data.json "1.0.0"]
   [stylefruits/gniazdo "1.1.4"]
   [org.jsoup/jsoup "1.13.1"]]
  :source-paths ["src/clj"]
  :java-source-paths ["src/java"]
  :resource-paths ["resources" "src/js/build"]
  :profiles {:dev  {:dependencies
                    [[org.clojure/clojure "1.10.1"]
                     [http-kit "2.5.0"]
                     [compojure "1.6.2"]
                     [ch.qos.logback/logback-classic "1.2.3"]
                     [eftest "0.5.9"]
                     [clj-kondo "2020.11.07"]]
                    :repl-options
                    {:init-ns repl}
                    :plugins
                    [[lein-ancient "0.6.15"]
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
