(ns shadow-eval.core
  (:require

    ;; evaluate
    [cljs.js :as cljs]
    [shadow.cljs.bootstrap.browser :as shadow.bootstrap]
    ;; view
    [reagent.dom :as dom]))

;; Set up eval environment
(defonce c-state (cljs/empty-state))

(defonce eval-ready? (atom false))

(defn eval*
  [source cb]
  (let [options {:eval    cljs/js-eval
                 ;; use the :load function provided by shadow-cljs, which uses the bootstrap build's
                 ;; index.transit.json file to map namespaces to files.
                 :load    (partial shadow.bootstrap/load c-state)
                 :context :expr}
        f (fn [x] (when (:error x)
                    (js/console.error (ex-cause (:error x))))
            (tap> x) (cb x))]
    (cljs/eval-str c-state (str source) "[test]" options f)))


;; Views

(defn editor []
  [:div
   [:button {:on-click
             #(eval* "(+ 1 2)"
                     (fn [result]
                       (print result)))} "eval !!"]])

(defn app []
  (if-not @eval-ready?
    [:div "loading ..."]
    [editor]))


(defn render []
  (dom/render [app] (js/document.getElementById "shadow-eval")))

(defn ^:dev/after-load init []
  (shadow.bootstrap/init c-state
                         {:path         "/js/bootstrap"
                          :load-on-init '#{shadow-eval.user}}
                         #(do
                            (reset! eval-ready? true)
                            (render)))
  (render))
