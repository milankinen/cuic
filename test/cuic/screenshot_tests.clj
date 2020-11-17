(ns cuic.screenshot-tests
  (:require [clojure.test :refer :all]
            [clojure.string :as string]
            [cuic.core :as c]
            [cuic.test :refer [deftest* is* browser-test-fixture]]
            [test-common :refer [todos-url]])
  (:import (javax.imageio ImageIO)
           (java.io ByteArrayInputStream)))

(use-fixtures
  :once
  (browser-test-fixture))

(defn- get-format [image-data]
  (with-open [iis (ImageIO/createImageInputStream (ByteArrayInputStream. image-data))]
    (some->> (iterator-seq (ImageIO/getImageReaders iis))
             (first)
             (.getFormatName)
             (string/lower-case))))

(deftest* screenshot-format-tests
  (c/goto todos-url)
  (testing "default screenshot"
    (is (= "png" (get-format (c/screenshot)))))
  (testing "jpeg screenshot"
    (is (contains? #{"jpeg" "jpg"} (get-format (c/screenshot {:format :jpeg}))))
    (is (< (count (c/screenshot {:format  :jpeg
                                 :quality 10}))
           (count (c/screenshot {:format  :jpeg
                                 :quality 100}))))))
