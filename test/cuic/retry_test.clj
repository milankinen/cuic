(ns cuic.retry-test
  (:require [clojure.test :refer :all]
            [cuic.impl.exception :as ex]
            [cuic.core :as c])
  (:import (cuic ExecutionException WaitTimeoutException)))

(defn throw-once [ex]
  (let [thrown (atom false)]
    (fn []
      (when-not @thrown
        (reset! thrown true)
        (throw ex))
      true)))

(use-fixtures :each (fn [t] (binding [c/*config* (assoc c/*config* :timeout 500)] (t))))

(deftest wait
  (testing "return value must be truthy"
    (is (= true (c/wait true)))
    (is (= "tsers!" (c/wait "tsers!")))
    (is (thrown? WaitTimeoutException (c/wait nil)))
    (is (thrown? WaitTimeoutException (c/wait false))))
  (testing "CUIC related retryable exceptions are captured but other exceptions are passed through"
    (let [stale-ex-f   (throw-once (ex/stale-node (RuntimeException. "Tsers")))
          runtime-ex-f (throw-once (RuntimeException. "Tsers"))
          js-error-f   (throw-once (ex/js-error "TypeError"))]
      (is (true? (c/wait (stale-ex-f))))
      (is (thrown? RuntimeException (c/wait (runtime-ex-f))))
      (is (thrown? ExecutionException (c/wait (js-error-f)))))))

