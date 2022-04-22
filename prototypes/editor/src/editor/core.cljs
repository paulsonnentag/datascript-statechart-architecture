(ns editor.core
  (:require
    [goog.dom :as gdom]
    [cljs.js :as cljs]
    [shadow.cljs.bootstrap.browser :as shadow.bootstrap]
    [reagent.dom :as dom]
    [reagent.core :as r]))

(defn input-value [evt]
  (-> evt .-target .-value))

;; Set up eval environment
(defonce c-state (cljs/empty-state))

(defonce !eval-ready? (atom false))

(defn eval*
  [source cb]
  (let [options {:eval    cljs/js-eval
                 ;; use the :load function provided by shadow-cljs, which uses the bootstrap build's
                 ;; index.transit.json file to map namespaces to files.
                 :load    (partial shadow.bootstrap/load c-state)
                 :context :expr}]
    (cljs/eval-str c-state (str source) nil options cb)))


(declare render)

(defn example []
  [:h1 "Hello world!"])

(def !source (r/atom "[:h1 \"Hello world!\"]"))
(def !error (r/atom nil))

(defn compile-template [source cb]
  (let [template-source (str "(fn []" source ")")]
    (eval*
      template-source
      cb)))


(defn error-boundary [comp]
  (r/create-class
    {:component-did-catch          (fn [this e info])
     :get-derived-state-from-error (fn [e]
                                     (js/console.log "error" e)
                                     (reset! !error "runtime error")
                                     #js {})
     :reagent-render               (fn [comp]
                                     (when-not @!error
                                       comp))}))

(defn editor []
  (let [source @!source
        error @!error]
    [:div.Editor
     [:div
      [:textarea
       {:value     source
        :on-change (fn [evt]
                     (let [new-source (input-value evt)]
                       (reset! !source new-source)
                       (compile-template
                         new-source
                         (fn [{:keys [error value]}]
                           (if error
                             (reset! !error (ex-message (ex-cause error)))
                             (do
                               (set! (.. js/editor -core -example) value)
                               (reset! !error nil)
                               (render)))))))}]
      (cond error
            [:div.Error error])]
     [:div
      [error-boundary
       [example]]]]))

(defn app []
  (if-not @!eval-ready?
    [:div "loading ..."]
    [editor]))


(defn render []
  (dom/render [app] (gdom/getElement "root")))

(defn ^:dev/after-load init []
  (shadow.bootstrap/init c-state
                         {:path         "/js/bootstrap"
                          :load-on-init '#{shadow-eval.user}}
                         #(do
                            (reset! !eval-ready? true)
                            (render)))
  (render))


