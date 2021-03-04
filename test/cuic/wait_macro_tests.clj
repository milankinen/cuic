(ns cuic.wait-macro-tests
  (:require [clojure.test :refer :all]
            [cuic.core :as c])
  (:import (cuic TimeoutException)))

(deftest wait-macro-tests
  (testing "expression is retried until it returns truthy value"
    (let [counter (atom 0)]
      (is (c/wait (let [v (swap! counter inc)]
                    (when (= 3 v)
                      v))))))
  (testing "exception is thrown if timeout exceeds"
    (binding [c/*timeout* 500]
      (is (thrown-with-msg?
            TimeoutException
            #"Timeout exceeded while waiting for truthy value from expression: \(= 1 2\)"
            (c/wait (= 1 2))))))
  (testing "timeout can be passed as option"
    (binding [c/*timeout* 1000000]
      (is (thrown-with-msg?
            TimeoutException
            #"Timeout exceeded while waiting for truthy value from expression: \(= 1 2\)"
            (c/wait (= 1 2) {:timeout 500})))))
  (testing "error message can be passed as options"
    (is (thrown-with-msg?
          TimeoutException
          #"tsers"
          (c/wait (= 1 2) {:timeout 500 :message "tsers"})))
    (is (thrown-with-msg?
          TimeoutException
          #"tsers"
          (c/wait (= 1 2) {:timeout 500 :message #(do "tsers")})))))

