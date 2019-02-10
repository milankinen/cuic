(ns cuic.impl.util
  (:require [clojure.tools.logging :refer [trace]]
            [cuic.impl.js-bridge :as js]
            [cuic.impl.exception :refer [call]]
            [cuic.impl.browser :refer [tools]])
  (:import (java.util Base64)
           (java.awt.image BufferedImage)
           (java.awt Image)
           (javax.imageio ImageIO)
           (java.io ByteArrayInputStream)))

(defn- px [num]
  (int (Math/floor (double num))))

(defn fit-rect [im {:keys [x y w h]}]
  (let [iw  (.getWidth im)
        ih  (.getHeight im)
        x'  (min iw (max 0 (px x)))
        y'  (min ih (max 0 (px y)))
        fit {:x x'
             :y y'
             :w (max 0 (- (min (px (+ x w)) iw) x'))
             :h (max 0 (- (min (px (+ y h)) ih) y'))}]
    (trace "Fit" x y w h "to" fit)
    fit))

(defn scroll-into-view! [node]
  (js/exec-in node "
     await window.__cuic_deps.scrollIntoView(this, {
        behavior: 'smooth',
        scrollMode: 'if-needed'
     })"))

(defn bounding-box [node]
  (js/exec-in node "
     var r = this.getBoundingClientRect();
     return {top: r.top, left: r.left, width: r.width, height: r.height};
  "))

(defn bbox-center [node]
  (let [{:keys [top left width height]} (bounding-box node)]
    {:x (+ left (/ width 2))
     :y (+ top (/ height 2))}))

(defn decode-base64 [^String data]
  (let [d (Base64/getDecoder)]
    (.decode d data)))

(defn scaled-screenshot [browser]
  (let [im  (-> (call #(.captureScreenshot (.getPage (tools browser)) nil nil nil true))
                (decode-base64)
                (ByteArrayInputStream.)
                (ImageIO/read))
        vps (-> (call #(.getLayoutMetrics (.getPage (tools browser))))
                (.getLayoutViewport))
        rx  (.getClientWidth vps)
        ry  (.getClientHeight vps)
        ih  (.getHeight im)
        iw  (.getWidth im)]
    (if (or (not= iw (int rx))
            (not= ih (int ry)))
      (let [scaled (.getScaledInstance im rx ry Image/SCALE_DEFAULT)
            sw     (.getWidth scaled nil)
            sh     (.getHeight scaled nil)
            ims    (BufferedImage. sw sh BufferedImage/TYPE_INT_ARGB)
            g      (.getGraphics ims)]
        (try (.drawImage g scaled 0 0 nil) (finally (.dispose g)))
        ims)
      im)))

(defn crop [^BufferedImage im x y w h]
  (let [r (fit-rect im {:x x :y y :w w :h h})]
    (.getSubimage im (:x r) (:y r) (:w r) (:h r))))

