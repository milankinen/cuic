(ns cuic.test
  "Utilities for writing concise and robust UI tests.

   Example usage:
   ```clojure
   (ns todomvc-tests
     (:require [clojure.test :refer :all]
               [cuic.core :as c]
               [cuic.test :refer [deftest* is* browser-test-fixture]]))

   (use-fixtures
     :once
     (browser-test-fixture))

   (defn todos []
     (->> (c/query \".todo-list li\")
          (map c/text-content)))

   (defn add-todo [text]
     (doto (c/find \".new-todo\")
       (c/fill text))
     (c/press 'Enter))

   (deftest* creating-new-todos
     (c/goto \"http://todomvc.com/examples/react\")
     (is* (= [] (todos)))
     (add-todo \"Hello world!\")
     (is* (= [\"Hello world!\"] (todos)))
     (add-todo \"Tsers!\")
     (is* (= [\"Hello world!\" \"Tsers!\"] (todos))))
   ```"
  (:require [clojure.test :refer [deftest is assert-expr do-report testing-contexts-str]]
            [clojure.string :as string]
            [clojure.java.io :as io]
            [cuic.core :as c]
            [cuic.chrome :as chrome])
  (:import (java.io File)
           (cuic TimeoutException)
           (cuic.internal AbortTestError)))

(set! *warn-on-reflection* true)

(defonce ^:no-doc ^:dynamic *current-cuic-test* nil)

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
                       {:value   (.getLatestValue e#)
                        :abort   true
                        :timeout e#})
                     (catch Throwable t#
                       (reset! last-report# {:type     :error
                                             :message  nil
                                             :expected '~form
                                             :actual   t#})
                       {:abort true})))]
     (when-let [ex# (:timeout result#)]
       (do-report {:type    :cuic/timeout
                   :message (ex-message ex#)
                   :expr    '~form}))
     (some-> @last-report# (do-report))
     result#))

;;;;

(def ^:dynamic *screenshot-options*
  "Options that [[cuic.test/deftest*]] adn [[cuic.test/is*]] will
   use for screenshots. Accepts all options accepted by [[cuic.core/screenhot]]
   plus `:dir` (`java.io.File` instance) defining directory, where the
   taken screenshots should be saved.

   ```clojure
   ;; Store taken screenshots under $PWD/__screenshots__ directory
   (use-fixtures
     :once
     (fn [t]
       (binding [*screenshot-options* (assoc *screenshot-options* :dir (io/file \"__screenshots__\"))]
         (t))))
   ```"
  {:dir     (io/file "target/screenshots")
   :format  :png
   :timeout 10000})

(defn ^:no-doc -try-take-screenshot [test-name context-s]
  (try
    (when-let [browser c/*browser*]
      (let [data (c/screenshot (assoc *screenshot-options* :browser browser))
            dir (doto ^File (:dir *screenshot-options*)
                  (.mkdirs))]
        (loop [base (-> (str (some-> test-name (string/replace #"\." "\\$")) context-s)
                        (string/lower-case)
                        (string/replace #"\s+" "-")
                        (string/replace #"[^a-z\-_0-9$]" ""))
               i 0]
          (let [n (str base (when (pos? i) (str "-" i)) "." (name (:format *screenshot-options*)))
                f (io/file dir n)]
            (if-not (.exists f)
              (do (io/copy data f)
                  (do-report {:type     :cuic/screenshot-taken
                              :filename (.getAbsolutePath f)}))
              (recur base (inc i)))))))
    (catch Exception ex
      (do-report {:type  :cuic/screenshot-failed
                  :cause ex}))))

(defn set-screenshot-options!
  "Globally resets the default screenshot options. Useful for
   example REPL usage. See [[cuic.test/*screenshot-options*]]
   for more details."
  [opts]
  {:pre [(map? opts)]}
  (alter-var-root #'*screenshot-options* (constantly opts)))

(def ^:dynamic *abort-immediately*
  "Controls the behaviour of immediate test aborting in case of
   [[cuic.test/is*]] failure. Setting this value to `false`
   means that the test run continues even if `is*` assertion fails
   (reflects the behaviour of the standard `clojure.test/is`).

   ```clojure
   ;; Run the whole test regardless of is* failures
   (use-fixtures
     :once
     (fn [t]
       (binding [*abort-immediately* false]
         (t))))
   ```"
  true)

(defn set-abort-immediately!
  "Globally resets the test aborting behaviour. Useful for example REPL
   usage. See [[cuic.test/*abort-immediately*]] for more details."
  [abort?]
  {:pre [(boolean? abort?)]}
  (alter-var-root #'*abort-immediately* (constantly abort?)))

(defmacro deftest*
  "Cuic's counterpart for `clojure.test/deftest`. Works identically to `deftest`
   but stops gracefully if test gets aborted due to an assertion failure
   in [[cuic.test/is*]]. Also captures a screenshot if test throws an
   unexpected error.

   See namespace documentation for the complete usage example."
  [name & body]
  `(deftest ~name
     (let [test-ns-s# ~(str *ns*)
           test-name-s# ~(str name)
           full-name# (str test-ns-s# "$$" test-name-s# "$$")]
       (try
         (binding [*current-cuic-test* {:ns test-ns-s# :name test-name-s#}]
           ~@body)
         (catch AbortTestError e#
           nil)
         (catch Throwable e#
           (-try-take-screenshot full-name# nil)
           (throw e#))))))

(defmacro is*
  "Basically a shortcut for `(is (c/wait <cond>))` but with some
   improvements to error reporting. See namespace documentation
   for the complete usage example.

   If the tested expression does not yield truthy value within the
   current [[cuic.core/*timeout*]], a assertion failure will be
   reported using the standard `clojure.test` reporting mechanism.
   However, the difference between `(is (c/wait <cond>))` and
   `(is* <cond>)` is that former reports the failure from `(c/wait <cond>)`
   whereas the latter reports the failure from `<cond>` which will
   produce much better error messages and diffs when using custom test
   reporters  such as [eftest](https://github.com/weavejester/eftest)
   or Cursive's test runner.

   Because of the nature of UI tests, first assertion failure will
   usually indicate the failure of the remaining assertions as well.
   If each of these assertions wait the timeout before giving up,
   the test run might prolong quite a bit. That's why `is*` aborts
   the current test run immediately after first failed assertion. This
   behaviour can be changed by setting [[cuic.test/*abort-immediately*]]
   to `false` in the surrounding scope with `binding` or globally
   with [[cuic.test/set-abort-immediately!]].

   **For experts only:** if you are writing a custom test reporter
   and want to hook into cuic internals, `is*` provides the following
   report types:

   ```clojure
   ;; (is* expr) timeout occurred
   {:type    :cuic/timeout
    :message <string>   ; error message of the timeout exception
    :expr    <any>      ; sexpr of the failed form
    }

   ;; Screenshot was time form the current browser
   {:type     :cuic/screenshot-taken
    :filename <string>  ; absolute path to the saved screenshot file
    }

   ;; Screenshot was failed for some reason
   {:type  :cuic/screenshot-failed
    :cause <throwable>   ; reason for failure
    }

   ;; Test run was aborted
   {:type :cuic/test-aborted
    :test <symbol>    ; aborted test fully qualified name
    :form <any>       ; sexpr of the form causing the abort
    }
   ```"
  [form]
  `(let [tname# (when *current-cuic-test*
                  (str (:ns *current-cuic-test*) "$$" (:name *current-cuic-test*) "$$"))
         res# (is (~eventually ~form))]
     (when (and (:abort res#) *abort-immediately*)
       (-try-take-screenshot tname# (testing-contexts-str))
       (do-report {:type :cuic/test-aborted
                   :test (when *current-cuic-test*
                           (symbol (:ns *current-cuic-test*) (:name *current-cuic-test*)))
                   :form '~form})
       (throw (AbortTestError.)))
     (:value res#)))

(defn browser-test-fixture
  "Convenience test fixture for UI tests. Launches a single Chrome instance
   and setups it as the default browser for [[cuic.core]] functions.

   Browser's headless mode is controlled either directly by the given options
   or indirectly by the `cuic.headless` system property. If you're developing
   tests with REPL, you probably want to set `{:jvm-opts [\"-Dcuic.headless=false\"]}`
   to your REPL profile so that tests are run in REPL are using non-headless
   mode but still using headless when run in CI.

   This fixture covers only the basic cases. For more complex test setups,
   you probably want to write your own test fixture.

   See namespace documentation for the complete usage example."
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
