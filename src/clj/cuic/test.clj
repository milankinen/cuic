(ns cuic.test
  (:require [clojure.test :as t]
            [cuic.core :refer [wait]]))

(defmacro is [form]
  `(let [rep#   (atom nil)
         report# #(some-> @rep# (t/do-report))]
     (try
       (let [res# (with-redefs [t/do-report #(reset! rep# %)] (wait (t/is ~form)))]
         (report#)
         res#)
       (catch Throwable t#
         (report#)
         nil))))
