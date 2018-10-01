(ns test-runner
  (:require [eftest.runner :refer [find-tests run-tests]]))

(defn run-tests-cli [& _]
  (println "Running tests...")
  (let [res (-> (find-tests "test")
                (run-tests {:multithread? false}))]
    (System/exit (+ (:fail res) (:error res)))))
