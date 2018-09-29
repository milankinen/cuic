(ns cuic.impl.util
  (:require [cuic.impl.browser :refer [c]]
            [cuic.impl.js-bridge :as js]))

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
