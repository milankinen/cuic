(ns test-runner
  (:require [clojure.string :as string]
            [eftest.runner :refer [find-tests run-tests]]))

(defn- include [names]
  (fn [t]
    (let [test-name (str (:ns (meta t)) "/" (:name (meta t)))]
      (some #(string/includes? test-name %) names))))

(defn run-tests-cli [& names]
  (let [tests (->> (find-tests "test")
                   (filter (if (seq names) (include names) (constantly true))))
        _     (println "Running" (count tests) "tests...")
        res   (run-tests tests {:multithread? false})]
    (System/exit (+ (:fail res) (:error res)))))
