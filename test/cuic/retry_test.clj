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

(deftest with-retry
  (testing "return value does not matter, try-retry always returns nil"
    (is (nil? (c/with-retry true)))
    (is (nil? (c/with-retry false)))
    (is (nil? (c/with-retry nil))))
  (testing "CUIC related retryable exceptions are captured but other exceptions are passed through"
    (let [stale-ex-f   (throw-once (ex/stale-node (RuntimeException. "Tsers")))
          runtime-ex-f (throw-once (RuntimeException. "Tsers"))
          js-error-f   (throw-once (ex/js-error "TypeError"))]
      (is (nil? (c/with-retry (stale-ex-f))))
      (is (thrown? RuntimeException (c/with-retry (runtime-ex-f))))
      (is (thrown? ExecutionException (c/with-retry (js-error-f))))))
  (testing "timeout exception is thrown if function does not resolve within timeout"
    (let [ex (is (thrown? WaitTimeoutException (c/with-retry (throw (ex/stale-node (RuntimeException. "Tsers"))))))]
      (is (ex/stale-node? (.getCause ex))))))

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
      (is (nil? (c/with-retry (stale-ex-f))))
      (is (thrown? RuntimeException (c/with-retry (runtime-ex-f))))
      (is (thrown? ExecutionException (c/with-retry (js-error-f)))))))

