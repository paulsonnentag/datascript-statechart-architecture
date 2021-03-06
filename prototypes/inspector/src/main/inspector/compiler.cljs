(ns inspector.compiler
  (:require [cljs.js :as cljs]
            [shadow.cljs.bootstrap.browser :as bootstrap]))

(defonce state (cljs/empty-state))

(def options
  {:eval    cljs/js-eval
   ;; use the :load function provided by shadow-cljs, which uses the bootstrap build's
   ;; index.transit.json file to map namespaces to files.
   :load    (partial bootstrap/load state)
   :context :statement})

(defonce !ready? (atom false))

(defn eval-src
  ([src cb]
   (eval-src src :statement cb))
  ([src context cb]
  (let [id (js/Math.random)]
    (if @!ready?
      (cljs/eval-str state (str src) nil (assoc options :context context) cb)
      (add-watch !ready? id #(when @!ready?
                                (remove-watch !ready? id)
                                (cljs/eval-str state (str src) nil options cb)))))))

(defn init [cb]
  (bootstrap/init
    state
    {:path         "/js/bootstrap"
     :load-on-init '#{shadow-eval.user}}
    #(do
       (reset! !ready? true)
       (cb))))