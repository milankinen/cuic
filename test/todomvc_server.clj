(ns todomvc-server
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [compojure.core :refer [routes GET]]
            [compojure.route :refer [files not-found]]
            [org.httpkit.server :refer [run-server]]
            [org.httpkit.client :as http])
  (:import (java.io Closeable)))

(defn- server-running? [port]
  (try
    (= 200 (:status @(http/get (str "http://127.0.0.1:" port))))
    (catch Exception e
      (.printStackTrace e)
      false)))

(defn start-server! [port]
  (let [app-dir (io/file "test/todomvc")
        html    (slurp (io/file app-dir "index.html"))
        handler (routes
                  (GET "/" []
                    {:body html :headers {"Content-Type" "text/html"}})
                  (files "/static" {:root (str (.getAbsolutePath app-dir) "/resources/public")})
                  (not-found "..."))
        stop!   (run-server handler {:ip "127.0.0.1" :port port})]
    (if-not (loop [n 30]
              (or (server-running? port)
                  (and (pos? n)
                       (do (Thread/sleep 100)
                           (recur (dec n))))))
      (throw (RuntimeException. "Could not start server")))
    (reify Closeable
      (close [_] (stop!)))))
