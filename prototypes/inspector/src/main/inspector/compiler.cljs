(ns inspector.compiler
  (:require [cljs.js :as cljs]
            [shadow.cljs.bootstrap.browser :as bootstrap]
            [reagent.core :as r]))

(defonce state (cljs/empty-state))

(def options
  {:eval    cljs/js-eval
   ;; use the :load function provided by shadow-cljs, which uses the bootstrap build's
   ;; index.transit.json file to map namespaces to files.
   :load    (partial bootstrap/load state)
   :context :statement})

(defonce !ready? (atom false))

(defn eval-expr [source cb]
  (let [id (js/Math.random)]
    (if @!ready?
      (cljs/eval-str state (str source) nil options cb)
      (add-watch !ready? id (fn [_ {ready? :val}]
                              (when ready?
                                (remove-watch !ready? id)
                                (cljs/eval-str state (str source) nil options cb)))))))

(defn init [cb]
  (bootstrap/init
    state
    {:path         "/js/bootstrap"
     :load-on-init '#{shadow-eval.user}}
    #(do
       (reset! !ready? true)
       (cb))))