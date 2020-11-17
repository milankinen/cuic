(ns cuic.test
  (:require [clojure.test :refer [deftest is assert-expr do-report testing-contexts-str]]
            [clojure.string :as string]
            [clojure.java.io :as io]
            [cuic.core :as c]
            [cuic.chrome :as chrome])
  (:import (java.io File)
           (cuic AbortTestError TimeoutException)))

(set! *warn-on-reflection* true)

(defonce ^:dynamic *in-cuic-test* nil)

(defonce ^:private eventually
  (gensym (str 'cuic.test/is-eventually-)))

(defmethod assert-expr eventually [msg [_ form]]
  `(let [last-report# (atom nil)
         result# (with-redefs [do-report #(reset! last-report# %)]
                   (try
                     {:value (c/wait ~(assert-expr msg form))}
                     (catch TimeoutException e#
                       (swap! last-report# #(or % {:type     :fail
                                                   :message  nil
                                                   :expected '~form
                                                   :actual   (list '~'not '~form)}))
                       {:abort true})
                     (catch Throwable t#
                       (reset! last-report# {:type     :error
                                             :message  nil
                                             :expected '~form
                                             :actual   t#})
                       {:abort true})))]
     (some-> @last-report# (do-report))
     result#))

;;;;

(def ^:dynamic *screenshot-options*
  {:dir     (io/file "target/screenshots")
   :format  :png
   :timeout 10000})

(defn -try-take-screenshot [test-name ^AbortTestError err]
  (try
    (when c/*browser*
      (let [data (c/screenshot)
            dir (doto ^File (:dir *screenshot-options*)
                  (.mkdirs))]
        (loop [base (-> (str (string/replace test-name #"\." "\\$")
                             "$$" (:context (.getDetails err)))
                        (string/lower-case)
                        (string/replace #"\s+" "-")
                        (string/replace #"[^a-z\-_0-9$]" ""))
               i 0]
          (let [n (str base (when (pos? i) "-" i) "." (name (:format *screenshot-options*)))
                f (io/file dir n)]
            (if-not (.exists f)
              (do (io/copy data f)
                  (do-report {:type     ::screenshot-taken
                              :filename (.getAbsolutePath f)}))
              (recur base (inc i)))))))
    (catch Exception ex
      (do-report {:type  ::screenshot-failed
                  :cause ex}))))

(defn set-screenshot-options! [opts]
  {:pre [(map? opts)]}
  (alter-var-root #'*screenshot-options* (constantly opts)))

(defmacro deftest* [name & body]
  `(deftest ~name
     (try
       (binding [*in-cuic-test* true]
         ~@body)
       (catch AbortTestError e#
         (do-report {:type ::test-aborted
                     :test ~(symbol (str *ns*) (str name))
                     :form (:form (.getDetails e#))})
         (-try-take-screenshot ~(str *ns* "$$" name) e#)
         nil))))

(defmacro is*
  [form]
  `(do (when-not *in-cuic-test*
         (throw (AssertionError. "cuic.test/is* can't be used outside of cuic.test/deftest*")))
       (let [res# (is (~eventually ~form))]
         (when (:abort res#)
           (let [details# {:form '~form :context (testing-contexts-str)}]
             (throw (AbortTestError. details#))))
         (:value res#))))

(defn browser-test-fixture
  ([] (browser-test-fixture {}))
  ([{:keys [headless options]
     :or   {headless (not= "false" (System/getProperty "cuic.headless"))
            options  {}}}]
   {:pre [(boolean? headless)
          (map? options)]}
   (fn browser-test-fixture* [t]
     (with-open [chrome (chrome/launch (assoc options :headless headless))]
       (binding [c/*browser* chrome]
         (t))))))
