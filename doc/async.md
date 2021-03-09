## Handling asynchrony 

Since the tested UI runs in a browser in a separate process from the 
test code, there is no quarantee that the UI is immediately in certain 
state after each test action. Not taking this into account will definitely 
result in flaky indeterminisic tests. This is a universal problem not only 
related to `cuic` but other UI testing libraries as well. 

Some libraries try to mitigate the async issues by introducing their 
own DSLs that combine actions, data reading and assertions into "chains" 
or similar constructs. `cuic` however, takes a different approach. Unlike 
in JavaScript, IO doesn't need to be asynchronous in JVM languages. `cuic` 
takes advance of this: each function call *blocks* if there are any asynchronous 
activity and returns *data structures* that can be interacted with other 
(possibly blocking) functions and standard Clojure language features. This 
makes test utilities composable and easy to debug. Control flow is also a 
lot easier to follow in the standard blocking code than with asynchronous
primitives and DSLs.

As a rule of thumb, `cuic` has three levels to handle asynchrony:

  1. `c/find` always waits until the searched element exists in DOM 
  2. Actions wait until the target element satisfies pre-conditions that are
     required for the performed action (e.g. clicked element becomes visible 
     and enabled)
  3. `c/wait` macro allows waiting for *any* other condition, blocking the 
     execution until the expected condition is satisfied 

### Element queries with `cuic.core/find` 

Based on the experience, in 90% of the cases, you're gonna query just one 
specific element at time: "click save button", "fill email field",  "type 
xyz to the search box", "check that cancel button is disabled". Because 
this is so common, single element queries has special semantics in `cuic`: 
when you're searching for the element, `cuic` expects it to exist and tries 
its best to find the element before giving up. If the element is not found 
immediately, `cuic` waits a little and tries again and again until the element 
appears in DOM or timeout exceeds. In case of timeout, an exception is thrown. 
This means that `find` **always** returns an element that exists in DOM.

```clojure 
(let [save-btn (c/find "#save-btn")]
  ;; Save-button is now a handle to the actual HTML element **in** page  
  ...)
```

Pay attention that element returned by `find` is a handle to the 
actual DOM node, not an abstract "description" how to find the node.
In other words, if the DOM node gets removed (either by user actions
or some background task or page reload), the handle becomes stale as 
well. That's why you should keep the element handles **only the time 
you need them** and discard them immediately after that (just don't
use the object, `cuic` and JVM GC handles the rest).

Element lookups are relatively cheap operations. Usually it's better
to use functions to get element on demand. This also makes your tests
cleaner because the function hides implementation details such as 
css selectors.

````clojure 
;;;;; try to avoid the following code

(let [save-btn (c/find "#app .footer button.save")]
  (c/click save-btn)
  ;; ...do something else...
  (c/click save-btn))

;;;;; instead, use function to defer the element lookup

(defn save-button []
  (c/find "#app .footer button.save"))

(c/click (save-button))
;; ...do something else...
(c/click (save-button))
````

### Interacting with elements

Separating element queries from actions is an intentional decision:
query ensures that the queried element *exists*. It does not test any
other conditions (such as that element is visible). However, if you 
want to interact with the element, certain other conditions must be 
satisfied as well before the action can be performed. For example, if you
want to click a button, the button must be visible in the viewport and
enabled. Hovering, in the other hand, can be done even if button is
not enabled. 

Every built-in action in `cuic` defines its own pre-conditions and waits
for them automatically if necessary. All of this is handled by `cuic` so
the only thing you need to do is to wait until action call returns.
Like with `find`, returning from action means that the action was 
performed successfully - any failures on pre-conditions will cause
an exception to be thrown.

```clojure 
(c/click (save-button))
;; here we can expect that save button **was** clicked
```

> **Attention!** `cuic` can guarantee that the action is peformed before 
> the function call returns. Hovever, it can't guarantee that the changes 
> caused by action are rendered to the DOM synchronously. Remember to use 
> `cuic`'s async primitives consistently - usage of `find` and lazy 
> node lookups everywhere, after actions as well. 

### `cuic.core/wait` - swiss army knife for **any** other situation

`(c/wait expr)` allows you to wait for *any* expression that can
return a truthy value. When the expression finally returns a truthy
value, it is returned to the caller. If `expr` returns a falsy value, 
`wait` will retry the same expression until it becomes non-falsy or 
timeouts (in which case a timeout exception is thrown). Note that 
because `wait` may run the given `expr`  multiple times, it's 
extremely important that `expr` does **not** have any side effects. 
**Never put an action inside `wait`.**

Once you learn how to use `wait`, you can make practically any
custom lookup or assertion your project needs:

```clojure 
;; Assertions
(is (c/wait (c/visible? (save-button))))
(is (c/wait (c/has-class? (save-button) "primary"))) 
(is (c/wait (re-find #"Changes saved" (c/inner-text (save-summary)))))
``` 

Also note that the waited expression doesn't even need be related 
to UI at all! Want to check that saved data is found from db? 
Use `wait` to check the expceted value:

```clojure 
(add-todo "Foo")
(add-todo "Bar")
(click-btn "Save")
;; Note that save request might take some time => wait until
;; todos are found from db
(is (c/wait (= #{"Foo" "Bar"} (set (map :text (query (get-db-conn) "SELECT text FROM todos"))))))
```

## Closing words

The blocking runtime such as JVM combined with sensible implicit waits enable
writing terse code that is (at least relatively) robust against asynchronous
behaviour. In the next section, we'll wrap this code into (re)runnable test 
cases and configure them so that they work with both local development and 
CI environments reliably. 
