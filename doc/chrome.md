## Launching Chrome

`cuic` expects that Chrome is installed in the running computer. After 
that, you can launch an instance by using [[cuic.chrome/launch]]. By
default, the instance is launched in headless-mode, but this can be 
changed by providing `:headless false` option. Based on the selected
mode, `cuic` sets appropriate defaults to browser's startup options.
If more customization is needed, the startup options can also be set
manually at the launch time. See [[cuic.chrome/launch]] for complete
reference of the available startup options.

```clojure 
;; launch headless chrome
(def headless-chrome (chrome/launch))

;; launch non-headless chrome
(def foreground-chrome (chrome/launch {:headless false}))
```

If you're using a non-standard Chrome/Chromium installation, you can
provide the executable path as a parameter to the `launch` invocation:

```clojure 
(def custom (chrome/launch {:headless false} "/custom/chrome/path"))
```

Each launched Chrome instance get their own user data directories, so 
they don't interfere or share any state (like local storage or cookies) 
with each other. When instances are terminated, their data directory is 
removed as well. `cuic` handles all of this setup and termination 
logic automatically.

Launched instances may be terminated by using [[cuic.chrome/terminate]].
They also implement `java.lang.AutoCloseable` so they can be used with
Clojure' `with-open` macro:

```clojure  
(with-open [chrome (chrome/launch)]
  ;; use `chrome`
  )
;; `chrome` is automatically terminated when `with-open` block ends
``` 

### Obtaining logs from browser instances

`cuic` logs information about the lifecycle and stdout/stderr of the 
launched browsers using `clojure.tools.logging`. To obtain these logs,
you must configure your logging library implementation to include 
`cuic.chrome` (or `cuic`) logger with the desired logging level:

   * `FATAL` - unexpected non-recoverable errors
   * `ERROR` - unexpected but recoverable errors
   * `DEBUG` - browser lifecycle events
   * `TRACE` - stdout and stderr of the browser process

> In practice, you don't need the logging when you're developing the app
> and tests locally. However, when running the tests in CI environment,
> having a log file with `TRACE` level logging has turned out a valuable
> artifact when debugging test failures.

## Setting the launched Chrome as default browser

Once you've obtained a Chrome instance, you have three different options 
to use it in `cuic.core` functions:

1. Pass it directly to each function invocation (`cuic`'s query functions 
   allow defining the browser explicitly, binding the retrieved element to 
   the used browser. See *"Multi-browser testing"* for more details).
3. Use dynamic `cuic.core/*browser*` variable and Clojure's `binding` macro. 
   This is the recommended option for test runs (see [testing guide](./tests.md) 
   for more details).
4. Set it globally as the default browser with [cuic.core/set-browser!]. 
   This is the recommended option for REPL (see [REPL setup](./repl.md) for 
   more details).

The next sections of this guide assume that you've set the default 
browser by using either methods two or three.
