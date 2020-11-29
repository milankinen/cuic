## Launching Chrome

`cuic` expects that Chrome is installed in the running computer. After 
that, you can launch an instance by using [[cuic.chrome/launch]]. By
default, the instance is launched in headless-mode, but this can be 
changed by providing `:headless false` option: `cuic` adds the appropriate 
command line switches to the browser process based on the selected mode. 

```clojure 
;; launch headless chrome
(def headless-chrome (chrome/launch))

;; launch non-headless chrome
(def foreground-chrome (chrome/launch {:headless false}))
```

If you're using a non-standard Chrome/Chromium installation, you can
provide the executable as a parameter to the `launch` invocation:

```clojure 
(def custom (chrome/launch {:headless false} "/custom/chrome/path"))
```

Each launched Chrome instance get their own user data directories, so 
they don't interfere or share any state (like local storage or cookies) 
with each other. When instances are terminated, their data directory is 
removed as well.

Launched instances may be terminated by using [[cuic.chrome/terminate]].
They also implement `java.lang.AutoCloseable` so they can be used with
Clojure' `with-open` macro:

```clojure  
(with-open [chrome (chrome/launch)]
  ;; use `chrome`
  )
;; `chrome` is automatically terminated when `with-open` block ends
``` 

## Setting the launched Chrome as default browser

Once you've obtained a Chrome instance, you have three different ways 
to use it in `cuic.core` functions:

1. Pass it directly to each function invocation
2. Use `cuic.core/*browser*` and Clojure's `binding` macro (recommended method 
   for test runs, see [testing guide](./tests.md) for more details).
3. Set it as the default browser with [cuic.core/set-browser!] (recommended
   method for REPL, see [REPL setup](./repl.md) for more details).

The next sections of this guide assume that you've set the default 
browser by using either methods two or three.
