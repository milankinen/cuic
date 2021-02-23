(ns cuic.protocol
  "Direct low-level access to Chrome DevTools via Chrome DevTools
   Protocol. Use [[cuic.chrome/devtools]] to obtain the to the
   browser's devtools client.

  See https://chromedevtools.github.io/devtools-protocol for
  the complete DevTools Protocol reference."
  (:require [cuic.core :refer [*timeout*]]
            [cuic.internal.cdt :as cdt]
            [cuic.internal.runtime :as runtime]
            [cuic.internal.dom :as dom]
            [cuic.internal.util :refer [check-arg rewrite-exceptions]]))

(defn invoke
  "Calls Chrome DevTools Protocol method and synchronously waits for the result,
   returning the received result or throwing an exception if invocation times
   out or other protocol error occurs.

   Command arguments must be a map containing only JSON serializable
   values. Likewise, the invocation result is deserialized back to Clojure
   map before it is returned to the caller.

   Use [[cuic.chrome/devtools]] to obtain the to the browser's devtools client.

   ```clojure
   ;; Get cookies for the current url
   (-> (invoke (chrome/devtools browser) \"Network.getCookies\")
       (:cookies))

   ;; Set _cuic_token cookie for localhost
   (invoke (chrome/devtools browser)
           \"Network.setCookie\"
           {:name   \"_cuic_token\"
            :value  \"tsers\"
            :domain \"localhost\"})
   ```"
  ([devtools cmd] (rewrite-exceptions (invoke devtools cmd {} *timeout*)))
  ([devtools cmd args] (rewrite-exceptions (invoke devtools cmd args *timeout*)))
  ([devtools cmd args timeout]
   (rewrite-exceptions
     (check-arg [cdt/cdt? "Chrome DevTools client"] [devtools "devtools"])
     (check-arg [string? "string"] [cmd "command"])
     (check-arg [map? "map"] [args "command arguments"])
     (check-arg [nat-int? "positive integer or zero"] [timeout "timeout"])
     (cdt/invoke {:cdt     devtools
                  :cmd     cmd
                  :args    args
                  :timeout timeout}))))

(defn on
  "Subscribes to the domain events, invoking the given callback each
   time when a new event occurs. Returns a subscription that can be
   canceled with [[cuic.devtools/off]]. Subscription also implements
   `java.lang.AutoCloseable` so it can be used with Clojure's
   `with-open` macro.

   ```
   ;; Enable log domain
   (invoke (chrome/devtools browser) \"Log.enable\")
   ;; Start printing browser console logs to stdout
   (tools/on (chrome/devtools browser) \"Log.entryAdded\" println)
   ```"
  [devtools event f]
  (rewrite-exceptions
    (check-arg [cdt/cdt? "Chrome DevTools client"] [devtools "devtools"])
    (check-arg [string? "string"] [event "event"])
    (check-arg [ifn? "function"] [f "callback function"])
    (cdt/on {:cdt      devtools
             :methods  #{event}
             :callback (fn [_ args] (f args))})))

(defn off
  "Disposes the subscription from [[cuic.devtools/on]]. Can be called
   multiple times per subscription but subsequent calls are no-ops.

   ```clojure
   (let [subscription (on ...)]
     ...
     (off subscription))
   ```"
  [subscription]
  (rewrite-exceptions
    (check-arg [cdt/subscription? "domain event subscription"] [subscription "subscription"])
    (cdt/off subscription)))

(defn node-id
  "Returns [NodeId](https://chromedevtools.github.io/devtools-protocol/tot/DOM/#type-NodeId)
   for the given html element.

   ```clojure
   (let [query-input (c/find \".query\")]
     ;; Describe query input node
     (invoke (chrome/devtools browser)
             \"DOM.describeNode\"
             {:nodeId (node-id query-input)}))
   ```"
  [element]
  (rewrite-exceptions
    (check-arg [dom/element? "html element"] [element "element"])
    (dom/get-node-id element)))

(defn object-id
  "Returns [RemoveObjectId](https://chromedevtools.github.io/devtools-protocol/tot/Runtime/#type-RemoteObjectId)
   for the given javascript object handle.

   ```clojure
   (let [query-input (c/find \".query\")]
     (invoke (chrome/devtools browser)
             \"Runtime.getProperties\"
             {:objectId (object-id query-input)
              :ownProperties true}))
   ```"
  [obj]
  (rewrite-exceptions
    (check-arg [runtime/js-object? "javascript object handle"] [obj "object"])
    (runtime/get-object-id obj)))
