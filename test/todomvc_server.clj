(ns todomvc-server
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [compojure.core :refer [routes GET]]
            [compojure.route :refer [files not-found]]
            [org.httpkit.server :refer [run-server]])
  (:import (java.io Closeable)))

(defn start-server! [port]
  (let [app-dir (io/file "test/todomvc")
        html    (slurp (io/file app-dir "index.html"))
        handler (routes
                  (GET "/" []
                    {:body html :headers {"Content-Type" "text/html"}})
                  (files "/static" {:root (str (.getAbsolutePath app-dir) "/resources/public")})
                  (not-found "..."))
        stop!   (run-server handler {:ip "127.0.0.1" :port port})]
    (reify Closeable
      (close [_] (stop!)))))
