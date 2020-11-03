(ns cuic.core
  (:refer-clojure :exclude [find type])
  (:require [clojure.string :as string]
            [cuic.chrome :refer [chrome? devtools page]]
            [cuic.internal.cdt :refer [invoke]]
            [cuic.internal.node :refer [wrap-node
                                        node?
                                        maybe-node?
                                        stale-as-nil
                                        stale-as-ex
                                        get-node-id
                                        get-node-name
                                        get-node-cdt
                                        rename]]
            [cuic.internal.page :refer [navigate-to
                                        navigate-forward
                                        navigate-back]]
            [cuic.internal.runtime :refer [get-window
                                           window?
                                           exec-js-code
                                           scroll-into-view-if-needed]]
            [cuic.internal.input :refer [mouse-move
                                         mouse-click
                                         type-kb
                                         press-key-down
                                         press-key-up]]
            [cuic.internal.html :refer [parse-document parse-element]]
            [cuic.internal.util :refer [rewrite-exceptions
                                        cuic-ex
                                        timeout-ex
                                        check-arg
                                        quoted
                                        decode-base64
                                        url-str?]])
  (:import (java.io File)
           (cuic TimeoutException)))

(set! *warn-on-reflection* true)

;;;
;;; config
;;;

(defn- -chars-per-minute [speed]
  (check-arg [#(or (contains? #{:slow :normal :fast :very-fast :tycitys} %)
                   (pos-int? %))
              "positive integer or one of #{:slow :normal :fast :tycitys}"]
             [speed "typing speed"])
  (case speed
    :slow 300
    :normal 600
    :fast 1200
    :very-fast 2400
    :tycitys 12000
    speed))

(def ^:dynamic *browser*
  nil)

(def ^:dynamic *timeout*
  5000)

(def ^:dynamic *typing-speed*
  :normal)

(def ^:dynamic *query-scope*
  nil)

(defn set-browser! [browser]
  (rewrite-exceptions
    (check-arg [chrome? "chrome instance"] [browser "value"])
    (alter-var-root #'*browser* (constantly browser))))

(defn set-timeout! [timeout]
  (rewrite-exceptions
    (check-arg [nat-int? "positive integer or zero"] [timeout "timeout"])
    (alter-var-root #'*timeout* (constantly timeout))))

(defn set-typing-speed! [speed]
  (rewrite-exceptions
    (-chars-per-minute speed)
    (alter-var-root #'*typing-speed* (constantly speed))))

;;;
;;; misc
;;;

(defn sleep [ms]
  {:pre [(nat-int? ms)]}
  (Thread/sleep ms))

(defmacro wait
  ([expr] `(wait ~expr cuic.core/*timeout*))
  ([expr timeout]
   `(let [timeout# ~timeout
          start-t# (System/currentTimeMillis)]
      (check-arg [nat-int? "positive integer or zero"] [timeout# "timeout"])
      (loop []
        (or ~expr
            (if (>= (- (System/currentTimeMillis) start-t#) timeout#)
              (throw (timeout-ex "Timeout exceeded while waiting for truthy"
                                 "value from expression:" ~(pr-str expr)))
              (recur)))))))

(defmacro in [scope & body]
  `(rewrite-exceptions
     (let [root-node# ~scope]
       (check-arg [node? "dom node"] [root-node# "scope root"])
       (binding [*query-scope* root-node#])
       ~@body)))

(defn as [node name]
  (rewrite-exceptions
    (check-arg [string? "string"] [name "node name"])
    (check-arg [node? "dom node"] [node "renamed node"])
    (rename node name)))

;;;
;;; core queries
;;;

(defn- -require-default-browser []
  (or *browser* (throw (cuic-ex "Default browser not set"))))

(defn window
  ([] (rewrite-exceptions (window (-require-default-browser))))
  ([browser]
   (rewrite-exceptions
     (check-arg [chrome? "chrome instance"] [browser "given browser"])
     (get-window (devtools browser)))))

(defn document
  ([] (rewrite-exceptions (document (-require-default-browser))))
  ([browser]
   (rewrite-exceptions
     (check-arg [chrome? "chrome instance"] [browser "given browser"])
     (let [cdt (devtools browser)
           res (invoke {:cdt  cdt
                        :cmd  "DOM.getDocument"
                        :args {:depth 0}})
           doc (wrap-node cdt (:root res) nil "document" nil)]
       (when (nil? doc)
         (throw (cuic-ex "Cound not get page document")))
       (vary-meta doc assoc ::document? true)))))

(defn active-element
  ([] (rewrite-exceptions (active-element (-require-default-browser))))
  ([browser]
   (rewrite-exceptions
     (stale-as-ex (cuic-ex "Couldn't get active element")
       (check-arg [chrome? "chrome instance"] [browser "given browser"])
       (let [cdt (devtools browser)
             res (invoke {:cdt  cdt
                          :cmd  "Runtime.evaluate"
                          :args {:expression "document.activeElement"}})
             obj (:result res)]
         (when obj
           (let [node (invoke {:cdt cdt :cmd "DOM.requestNode" :args obj})]
             (wrap-node cdt node nil (:description obj) nil))))))))

(defn find [selector]
  (rewrite-exceptions
    (check-arg [#(or (string? %) (map? %)) "string or map"] [selector "selector"])
    (loop [selector selector]
      (if (map? selector)
        (let [{:keys [from by as timeout]
               :or   {from    (or *query-scope* (document))
                      timeout *timeout*}} selector
              _ (check-arg [node? "node"] [from "from scope"])
              _ (check-arg [string? "string"] [by "selector"])
              _ (check-arg [#(or (string? %) (nil? %)) "string"] [as "alias"])
              _ (check-arg [nat-int? "positive integer or zero"] [timeout "timeout"])
              cdt (devtools (-require-default-browser))
              ctx-id (or (stale-as-nil (get-node-id from))
                         (throw (cuic-ex "Could not find node" (quoted (or as by)) "because"
                                         "context node" (quoted (get-node-name from)) "does not"
                                         "exist anymore")))
              start-t (System/currentTimeMillis)]
          (loop []
            (let [result (invoke {:cdt  cdt
                                  :cmd  "DOM.querySelectorAll"
                                  :args {:nodeId ctx-id :selector by}})
                  node-ids (:nodeIds result)
                  n-nodes (count node-ids)
                  elapsed (- (System/currentTimeMillis) start-t)]
              (case n-nodes
                0 (if (< elapsed timeout)
                    (do (sleep (min 100 (- *timeout* elapsed)))
                        (recur))
                    (throw (cuic-ex "Could not find node" (quoted as) "from"
                                    (quoted (get-node-name from)) "with selector"
                                    (quoted by) "in" timeout "milliseconds")))
                1 (or (stale-as-nil (wrap-node cdt {:nodeId (first node-ids)} from as by))
                      (recur))
                (throw (cuic-ex "Found too many" (str "(" n-nodes ")") (quoted as)
                                "nodes from" (quoted (get-node-name from))
                                "with selector" (quoted by)))))))
        (recur {:by selector})))))

(defn query [selector]
  (rewrite-exceptions
    (check-arg [#(or (string? %) (map? %)) "string or map"] [selector "selector"])
    (loop [selector selector]
      (if (map? selector)
        (let [{:keys [from by as]
               :or   {from (or *query-scope* (document))}} selector
              _ (check-arg [node? "node"] [from "from scope"])
              _ (check-arg [string? "string"] [by "selector"])
              _ (check-arg [string? "string"] [as "alias"])
              cdt (devtools (-require-default-browser))
              ctx-id (or (stale-as-nil (get-node-id from))
                         (throw (cuic-ex "Could not query" (quoted as) "nodes with selector"
                                         (quoted by) "because context node" (quoted (get-node-name from))
                                         "does not exist anymore")))]
          (->> (invoke {:cdt  cdt
                        :cmd  "DOM.querySelectorAll"
                        :args {:nodeId ctx-id :selector by}})
               (:nodeIds)
               (keep #(stale-as-nil (wrap-node cdt {:nodeId %} from as by)))
               (vec)
               (doall)
               (not-empty)))
        (recur {:by selector})))))

;;;
;;; js code execution
;;;

(defn- -exec-js [code args this]
  (let [cdt (if this
              (get-node-cdt this)
              (devtools (-require-default-browser)))
        result (exec-js-code {:cdt     cdt
                              :code    code
                              :args    args
                              :context this})]
    (if-let [error (:error result)]
      (throw (cuic-ex (str "JavaScript error occurred:\n\n" error)))
      (:return result))))

(defn eval-js
  ([expr]
   (rewrite-exceptions (eval-js expr {} (window))))
  ([expr args]
   (rewrite-exceptions (eval-js expr args (window))))
  ([expr args this]
   (rewrite-exceptions
     (check-arg [string? "string"] [expr "expression"])
     (check-arg [map? "map"] [args "call arguments"])
     (check-arg [#(or (node? %) (window? %)) "node or window"] [this "this binding"])
     (stale-as-ex (cuic-ex "Can't evaluate JavaScript expression on" (quoted this)
                           "because node does not exist anymore")
       (-exec-js (str "return " expr ";") args this)))))

(defn exec-js
  ([body]
   (rewrite-exceptions (exec-js body {} (window))))
  ([body args]
   (rewrite-exceptions (exec-js body args (window))))
  ([body args this]
   (rewrite-exceptions
     (check-arg [string? "string"] [body "function body"])
     (check-arg [map? "map"] [args "call arguments"])
     (check-arg [#(or (node? %) (window? %)) "node or window"] [this "this binding"])
     (stale-as-ex (cuic-ex "Can't execute JavaScript code on" (quoted this)
                           "because it does not exist anymore")
       (-exec-js body args this)))))

;;;
;;; properties
;;;

(defn- -prop-ex [node desc]
  (cuic-ex "Can't resolve" desc "from node" (quoted node)
           "because node does not exist anumore"))

(defn- -js-prop
  ([node js-expr] (-js-prop node js-expr {}))
  ([node js-expr args]
   (let [code (str "return " (string/replace js-expr #"\n\s+" "") ";")]
     (-exec-js code args node))))

(defn -bb [node]
  (-exec-js "let r = this.getBoundingClientRect();
             return {
               top: r.top,
               left: r.left,
               width: r.width,
               height: r.height
             };" {} node))

(defn client-rect [node]
  (rewrite-exceptions
    (check-arg [maybe-node? "dom node"] [node "node"])
    (stale-as-ex (-prop-ex node "node visibility")
      (-bb node))))

(defn visible? [node]
  (rewrite-exceptions
    (check-arg [maybe-node? "dom node"] [node "node"])
    (stale-as-ex (-prop-ex node "node visibility")
      (-js-prop node "!!this.offsetParent"))))

(defn inner-text [node]
  (rewrite-exceptions
    (check-arg [maybe-node? "dom node"] [node "node"])
    (stale-as-ex (-prop-ex node "inner text")
      (-js-prop node "this.innerText"))))

(defn text-content [node]
  (rewrite-exceptions
    (check-arg [maybe-node? "dom node"] [node "node"])
    (stale-as-ex (-prop-ex node "text content")
      (-js-prop node "this.textContent"))))

(defn outer-html [node]
  (rewrite-exceptions
    (check-arg [maybe-node? "dom node"] [node "node"])
    (stale-as-ex (-prop-ex node "outer html")
      (let [cdt (get-node-cdt node)
            node-id (get-node-id node)
            html (-> (invoke {:cdt  cdt
                              :cmd  "DOM.getOuterHtml"
                              :args {:nodeId node-id}})
                     (:outerHtml))]
        (if (::document? (meta node))
          (parse-document html)
          (parse-element html))))))

(defn value [node]
  (rewrite-exceptions
    (check-arg [maybe-node? "dom node"] [node "node"])
    (stale-as-ex (-prop-ex node "value")
      (-js-prop node "this.value"))))

(defn options [node]
  (rewrite-exceptions
    (check-arg [maybe-node? "dom node"] [node "node"])
    (stale-as-ex (-prop-ex node "options")
      (-js-prop node "Array.prototype.slice
                      .call(this.options || [])
                      .map(({value, text, selected}) => ({ value, text, selected }))"))))

(defn attrs [node]
  (rewrite-exceptions
    (check-arg [maybe-node? "dom node"] [node "node"])
    (stale-as-ex (-prop-ex node "attributes")
      (let [cdt (get-node-cdt node)]
        (->> (invoke {:cdt  cdt
                      :cmd  "DOM.getAttributes"
                      :args {:nodeId (get-node-id node)}})
             (:attributes)
             (partition-all 2 2)
             (map (fn [[k v]] [(keyword k) v]))
             (into {}))))))

(defn classes [node]
  (rewrite-exceptions
    (check-arg [maybe-node? "dom node"] [node "node"])
    (as-> (attrs node) $
          (get $ :class "")
          (string/split $ #"\s+")
          (map string/trim $)
          (remove string/blank? $)
          (set $))))

(defn has-class? [node class]
  (rewrite-exceptions
    (check-arg [maybe-node? "dom node"] [node "node"])
    (check-arg [string? "string"] [class "tested class name"])
    (contains? (classes node) class)))

(defn matches? [node selector]
  (rewrite-exceptions
    (check-arg [maybe-node? "dom node"] [node "node"])
    (check-arg [string? "string"] [selector "tested css selector"])
    (stale-as-ex (cuic-ex "Can't match css selector to node" (quoted node)
                          "because node does not exist anumore")
      (boolean (-js-prop node "(function(n){try{return n.matches(s)}catch(_){}})(this)" {:s selector})))))

(defn has-focus? [node]
  (rewrite-exceptions
    (check-arg [maybe-node? "dom node"] [node "node"])
    (stale-as-ex (-prop-ex node "focus")
      (-js-prop node "document.activeElement === this"))))

(defn checked? [node]
  (rewrite-exceptions
    (check-arg [maybe-node? "dom node"] [node "node"])
    (stale-as-ex (-prop-ex node "checked status")
      (-js-prop node "!!this.checked"))))

(defn disabled? [node]
  (rewrite-exceptions
    (check-arg [maybe-node? "dom node"] [node "node"])
    (stale-as-ex (-prop-ex node "disabled status")
      (-js-prop node "!!this.disabled"))))

;;;
;;; actions
;;;

(defn- -wait-visible [node]
  (try
    (wait (-js-prop node "!!this.offsetParent"))
    (catch TimeoutException _
      false)))

(defn- -wait-enabled [node]
  (try
    (wait (-js-prop node "!this.disabled"))
    (catch TimeoutException _
      false)))

(defn goto
  ([url]
   (rewrite-exceptions (goto url *timeout*)))
  ([url timeout]
   (rewrite-exceptions
     (check-arg [url-str? "valid url string"] [url "url"])
     (check-arg [nat-int? "positive integer or zero"] [timeout "timeout"])
     (navigate-to (page (-require-default-browser)) url timeout)
     nil)))

(defn go-back
  ([] (go-back *timeout*))
  ([timeout]
   (rewrite-exceptions
     (check-arg [nat-int? "positive integer or zero"] [timeout "timeout"])
     (navigate-back (page (-require-default-browser)) timeout)
     nil)))

(defn go-forward
  ([] (go-forward *timeout*))
  ([timeout]
   (rewrite-exceptions
     (check-arg [nat-int? "positive integer or zero"] [timeout "timeout"])
     (navigate-forward (page (-require-default-browser)) timeout)
     nil)))

(defn type
  ([input] (type input *typing-speed*))
  ([input speed]
   (rewrite-exceptions
     (check-arg [#(or (string? %) (simple-symbol? %)) "string or keycode symbol"] [input "typed input"])
     (let [cdt (devtools (-require-default-browser))]
       (type-kb cdt (if (symbol? input) [input] input) (-chars-per-minute speed))
       nil))))

(defn keydown [keycode]
  (rewrite-exceptions
    (check-arg [simple-keyword? "string"] [keycode "keycode"])
    (let [cdt (devtools (-require-default-browser))]
      (press-key-down cdt keycode))))

(defn keyup [keycode]
  (rewrite-exceptions
    (check-arg [simple-keyword? "string"] [keycode "keycode"])
    (let [cdt (devtools (-require-default-browser))]
      (press-key-up cdt keycode))))

(defn scroll-into-view [node]
  (rewrite-exceptions
    (check-arg [maybe-node? "dom node"] [node "target node"])
    (stale-as-ex (cuic-ex "Can't scroll node" (quoted (get-node-name node))
                          "into view because node does not exist anymore")
      (when-not (-wait-visible node)
        (throw (cuic-ex "Can't scroll node" (quoted (get-node-name node))
                        "into view because node is not visible")))
      (scroll-into-view-if-needed node)
      node)))

(defn hover [node]
  (rewrite-exceptions
    (check-arg [maybe-node? "dom node"] [node "hover target"])
    (stale-as-ex (cuic-ex "Can't hover over node" (quoted (get-node-name node))
                          "because node does not exist anymore")
      (when-not (-wait-visible node)
        (throw (cuic-ex "Can't hover over node" (quoted (get-node-name node))
                        "because node is not visible")))
      (scroll-into-view-if-needed node)
      (let [{:keys [top left width height]} (-bb node)
            cdt (get-node-cdt node)
            x (+ left (/ width 2))
            y (+ top (/ height 2))]
        (when (or (zero? width)
                  (zero? height))
          (throw (cuic-ex "Node" (quoted (get-node-name node))
                          "is not hoverable")))
        (mouse-move cdt {:x x :y y})
        node))))

(defn click [node]
  (rewrite-exceptions
    (check-arg [maybe-node? "dom node"] [node "clicked node"])
    (stale-as-ex (cuic-ex "Can't click node" (quoted (get-node-name node))
                          "because node does not exist anymore")
      (when-not (-wait-visible node)
        (throw (cuic-ex "Can't click node" (quoted (get-node-name node))
                        "because node is not visible")))
      (scroll-into-view-if-needed node)
      (when-not (-wait-enabled node)
        (throw (cuic-ex "Can't click node" (quoted (get-node-name node))
                        "because node is disabled")))
      (let [{:keys [top left width height]} (-bb node)
            cdt (get-node-cdt node)
            x (+ left (/ width 2))
            y (+ top (/ height 2))]
        (when (or (zero? width)
                  (zero? height))
          (throw (cuic-ex "Node" (quoted (get-node-name node))
                          "is not clickable")))
        (mouse-move cdt {:x x :y y})
        (mouse-click cdt {:x x :y y :button :left})
        node))))

(defn focus [node]
  (rewrite-exceptions
    (check-arg [maybe-node? "dom node"] [node "focused node"])
    (stale-as-ex (cuic-ex "Can't focus on node" (quoted (get-node-name node))
                          "because node does not exist anymore")
      (when-not (-wait-visible node)
        (throw (cuic-ex "Can't focus on node" (quoted (get-node-name node))
                        "because node is not visible")))
      (scroll-into-view-if-needed node)
      (when-not (-wait-enabled node)
        (throw (cuic-ex "Can't focus on node" (quoted (get-node-name node))
                        "because node is disabled")))
      (let [cdt (get-node-cdt node)]
        (invoke {:cdt  cdt
                 :cmd  "DOM.focus"
                 :args {:nodeId (get-node-id node)}})
        node))))

(defn select-text [node]
  (rewrite-exceptions
    (check-arg [maybe-node? "dom node"] [node "selected node"])
    (stale-as-ex (cuic-ex "Can't select text from node" (quoted (get-node-name node))
                          "because node does not exist anymore")
      (when-not (-wait-visible node)
        (throw (cuic-ex "Can't select text from node" (quoted (get-node-name node))
                        "because node is not visible")))
      (scroll-into-view-if-needed node)
      (when-not (-wait-enabled node)
        (throw (cuic-ex "Can't select text from node" (quoted (get-node-name node))
                        "because node is disabled")))
      (-exec-js "this.select()" {} node)
      node)))

(defn clear-text [node]
  (rewrite-exceptions
    (check-arg [maybe-node? "dom node"] [node "cleared node"])
    (stale-as-ex (cuic-ex "Can't clear text from node" (quoted (get-node-name node))
                          "because node does not exist anymore")
      (when-not (-wait-visible node)
        (throw (cuic-ex "Can't clear text from node" (quoted (get-node-name node))
                        "because node is not visible")))
      (scroll-into-view-if-needed node)
      (when-not (-wait-enabled node)
        (throw (cuic-ex "Can't clear text from node" (quoted (get-node-name node))
                        "because node is disabled")))
      (-exec-js "this.select()" {} node)
      (type-kb (get-node-cdt node) ['Backspace] 0)
      node)))

(defn fill
  ([node text] (fill node text *typing-speed*))
  ([node text speed]
   (rewrite-exceptions
     (check-arg [maybe-node? "dom node"] [node "filled node"])
     (check-arg [string? "string"] [text "typed text"])
     (stale-as-ex (cuic-ex "Can't fill node" (quoted (get-node-name node))
                           "because node does not exist anymore")
       (when-not (-wait-visible node)
         (throw (cuic-ex "Can't fill node" (quoted (get-node-name node))
                         "because node is not visible")))
       (scroll-into-view-if-needed node)
       (when-not (-wait-enabled node)
         (throw (cuic-ex "Can't fill node" (quoted (get-node-name node))
                         "because node is disabled")))
       (-exec-js "this.select()" {} node)
       (type 'Backspace)
       (type text speed)
       node))))

(defn choose
  [node & options]
  (rewrite-exceptions
    (check-arg [node? "dom node"] [node "updated node"])
    (check-arg [#(every? string? %) "strings"] [options "selected options"])
    (stale-as-ex (cuic-ex "Can't choose options from node" (quoted node)
                          "because node does not exist anymore")
      (when-not (-wait-visible node)
        (throw (cuic-ex "Can't choose options from node" (quoted (get-node-name node))
                        "because node is not visible")))
      (scroll-into-view-if-needed node)
      (when-not (-wait-enabled node)
        (throw (cuic-ex "Can't choose options from node" (quoted (get-node-name node))
                        "because node is disabled")))
      (-exec-js "
        if (this.nodeName.toLowerCase() !== 'select') throw new Error('Not a select node');
        console.log(vals)
        for (let o of this.options) {
          console.log(o)
          if ((o.selected = vals.includes(o.value)) && !this.multiple) {
            break;
          }
        }
        this.dispatchEvent(new Event('input', {bubbles: true}));
        this.dispatchEvent(new Event('change', {bubbles: true}));
        " {:vals options} node)
      nil)))

(defn add-files [node & files]
  (rewrite-exceptions
    (check-arg [maybe-node? "dom node"] [node "input node"])
    (doseq [file files]
      (check-arg [#(instance? File %) "file instance"] [file "file"])
      (when-not (.exists ^File file)
        (throw (cuic-ex "File does not exist:" (.getName ^File file)))))
    (stale-as-ex (cuic-ex "Can't add files to node" (quoted (get-node-name node))
                          "because node does not exist anymore")
      (when-not (-wait-visible node)
        (throw (cuic-ex "Can't add files to node" (quoted (get-node-name node))
                        "because node is not visible")))
      (scroll-into-view-if-needed node)
      (when-not (-wait-enabled node)
        (throw (cuic-ex "Can't add files to node" (quoted (get-node-name node))
                        "because node is disabled")))
      (when (seq files)
        (let [cdt (get-node-cdt node)]
          (invoke {:cdt  cdt
                   :cmd  "DOM.setFileInputFiles"
                   :args {:nodeId (get-node-id node)
                          :files  (mapv #(.getAbsolutePath ^File %) files)}})
          node)))))

(defn screenshot
  ([] (rewrite-exceptions (screenshot {})))
  ([{:keys [format
            quality
            timeout]
     :or   {format  :png
            quality 50
            timeout *timeout*}}]
   (rewrite-exceptions
     (check-arg [#{:jpeg :png} "either :jgep or :png"] [format "format"])
     (check-arg [#(and (integer? %) (<= 0 % 100)) "between 0 and 100"] [quality "quality"])
     (check-arg [nat-int? "positive integer or zero"] [timeout "timeout"])
     (let [cdt (devtools (-require-default-browser))
           res (invoke {:cdt     cdt
                        :cmd     "Page.captureScreenshot"
                        :args    {:format      (name format)
                                  :quality     quality
                                  :fromSurface true}
                        :timeout timeout})]
       (decode-base64 (:data res))))))
