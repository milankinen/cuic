## Multi-browser testing

Let's assume that we have a chat application, and we want to test that
the messages are readable by another users in real-time after they've 
been sent. This is not possible to test using single browser only because 
we'd potentially need to refresh the browser with another user credentials
after we've sent a message. So let's create a multi-browser setup!

### Test fixture setup for multiple browsers

Multi-browser test fixture setup is a bit more complex than setup with a single 
browser, because we need a symbol for the browser instances that are accessible
in our test cases to "switch" the browser. So, whereas normally you could use
`browser-test-fixture`, for multi-browser tests, you must define the test fixture
by yourself. Fortunately `cuic` uses separate profiles for each opened browser
instance so that they don't for example share sessions, thus can be used to
simulate different simultaneous users. 

Here an example implementation that I've used to launch multiple browsers for
different users:

```clj
;; This dynamic variable contains the opened browser instances
(def ^:private ^:dynamic *browsers* {})

(defn launch-test-chrome [options]
  ;; Just copying browser-test-fixture browser setup here!
  (let [headless (if-some [headless (:headless options)]
                   headless
                   (not= "false" (System/getProperty "cuic.headless")))]
    (chrome/launch (assoc options :headless headless))))

(defn get-browser [user]
  (or (get *browsers* user)
      (throw (RuntimeException. (str "Browser not found for user " user)))))
      
(defn init-user-session [browser user]
  ;; Assuming that c/*base-url* is set, we could also run e.g. any 
  ;; login steps here if application requires login. Also note that 
  ;; we need to explicitly define the browser for c/goto because we 
  ;; haven't set any default browser yet!
  (c/goto "/" {:browser browser}))

(defn multibrowser-fixture
  ([users options]
   {:pre [(seqable? users)
          (every? keyword? users)]}
   (if-let [user (first users)]
     (fn [t]
       (with-open [browser (launch-test-chrome options)]
         (init-user-session browser user)
         (binding [*browsers* (assoc *browsers* user browser)]
           ((multibrowser-fixture (next users) options) t))))
     (fn [t] (t))))
  ([users]
   (multibrowser-fixture users {})))

;; Usage in test suites
(use-fixtures
  :once
  (my-server-fixture '...)
  (multibrowser-fixture [:bob :alice]))
```

### Switching browser in tests

`cuic` itself doesn't care how many browsers are opened for tests. The API
and functions are designed so that they're decoupled from the browser
management, and it's up to your preferences to choose how to manage the 
browser switching.

1. Rebind `c/*browser*` locally
2. Pass the browser explicitly for each function call

I personally prefer option 1 because it allows to keep the testing dsl
clean of boilerplate but still be explicit in the test cases. The basic
idea is that I write functions like I'd write them for single browser:

```clj 
(defn messages []
  (->> (c/query {:by ".message" :in (c/find "#messages")})
       (map c/inner-text)))

(defn status-text []
  (-> (c/find "#status-area")
      (c/inner-text)))

(defn write-message [text]
  (doto (c/find "#new-message")
    (c/clear-text)
    (c/fill text))
  (c/press 'Enter))

(defn send-message []
  (doto (c/find "#send")
    (c/click)))

(deftest* chat-message-visibility-test
  (binding [c/*browser* (get-browser :alice)]
    (is* (= "" (status-text)))
    (is* (= [] (messages))))
  (binding [c/*browser* (get-browser :bob)]
    (write-message "tsers!"))
  (binding [c/*browser* (get-browser :alice)]
    (is* (= "Bob is writing..." (status-text)))
    (is* (= [] (messages))))
  (binding [c/*browser* (get-browser :bob)]
    (send-message))
  (binding [c/*browser* (get-browser :alice)]
    (is* (= "" (status-text)))
    (is* (= ["tsers!"] (messages)))))
```

If you know that the majority of tests require multiple browsers, you can 
also pass the browser as a parameter to avoid `binding` boilerplate. Note 
that the browser is required only for query functions (like `c/find` and 
`c/query`) and elements returned by those functions are bound to the browser 
that was used to get them. 

```clj 
(defn messages [browser]
  (let [container (c/find {:by "#message"
                           :in browser})]
    (->> (c/query {:by ".message" :in container})
         (map c/inner-text))))

(defn status-text [browser]
  (-> (c/find {:by "#status-area" :in browser})
      (c/inner-text)))

(defn write-message [browser text]
  (doto (c/find {:by "#new-message" :in browser})
    (c/clear-text)
    (c/fill text))
  (c/press 'Enter))

(defn send-message [browser]
  (doto (c/find {:by "#send" :in browser})
    (c/click)))

(deftest* chat-message-visibility-test
  (let [bob (get-browser :bob)
        alice (get-browser :alice)]
    (is* (= "" (status-text alice)))
    (is* (= [] (messages alice)))
    (write-message bob "tsers!")
    (is* (= "Bob is writing..." (status-text alice)))
    (is* (= [] (messages alice)))
    (send-message bob)
    (is* (= "" (status-text alice)))
    (is* (= ["tsers!"] (messages alice)))))
```

### REPL with multiple browsers

Multi-browser setup with REPL is pretty straightforward: we can reuse our
`launch-test-chrome` and `init-user-session` to create and initialize browser
session and then use clojure's `alter-var-root` to store them into our 
`*browsers*` symbol:

```clj 
(defn launch-browsers! [users]
  ;; Terminating previous browsers before launching new ones
  (doseq [[_ browser] *browsers*]
    (try
      (chrome/terminate browser)
      (catch Exception e
        (.printStackTrace e))))
  ;; Then launch new browsers, one by one
  (alter-var-root #'*browsers* (constantly {}))
  (doseq [user users]
    (let [browser (launch-test-chrome {:headless false})]
      (init-user-session browser user)
      (alter-var-root #'*browsers* assoc user browser))))
```

The main problem with REPL in multi-browser setup is how to easily
switch the browser. If you've implemented browser switching by rebinding 
the `c/*browser*` symbol, you can use `(c/set-browser!)` in REPL every
time you want to switch the default browser. In such cases I've added the
following comment form into my test case namespaces:

```clj 
(comment
  ;; Evaluate this when you start writing/debugging your test(s)
  (launch-browsers! [:bob :alice])

  ;; Evaluate this every time when you want to switch the browser to "Bob"
  (c/set-browser! (get-browser :bob))
  ;; Evaluate this every time when you want to switch the browser to "Alice"
  (c/set-browser! (get-browser :alice))
  -)
```

If you've used explicit browser instance passing to implement the browser 
switching, the setup is even simpler:

```clj 
(comment
  ;; Evaluate this when you start writing/debugging your test(s)
  (launch-browsers! [:bob :alice])

  ;; Evaluate this every time when you want to switch the browser to "Bob"
  (def browser (get-browser :bob))
  ;; Evaluate this every time when you want to switch the browser to "Alice"
  (def browser (get-browser :alice))
  -)
```

Note that you can use the same `browser` symbol that you're using in your test 
functions. By this, you can even evaluate individual forms inside your functions 
as well, using to the latest "switched" browser!

### Closing words

As you can see, testing with multiple browsers isn't much more difficult compared
to the normal single browser testing. Yes, it requires some extra setup and utilities
for the browser management, but when you get them up and running, it's pretty much 
the same. And because the browser management and switching is built by you, you can 
modify and tailor it to fit your needs as much as you like.
