(ns inspector.app
  (:require [statecharts.core :as fsm]
            [datascript.core :as d]
            [goog.dom :as gdom]
            [reagent.dom :as dom]
            [reagent.core :as r]
            [posh.reagent :as p]))

(defn input-value [evt]
  (-> evt .-target .-value))

(def completion-machine
  (fsm/machine
    {:id      :completion-machine
     :initial :pending
     :states
     {:pending {:on
                {:toggle :done}}
      :done    {:on
                {:toggle :pending}}}}))


(def schema {:statechart/machine {}})

(defonce conn (d/create-conn schema))

(p/posh! conn)

(d/transact! conn [{:db/id              1
                    :statechart/machine completion-machine}])

; ui id

(def next-id! (atom 0))

(defn get-ui-id []
  (let [id @next-id!]
    (swap! next-id! inc)
    id))

; event selectors

(defn get-element-path
  ([element]
   (get-element-path element []))

  ([^js element child-path]
   (let [dataset (.-dataset element)
         view-id (.-viewId dataset)
         db-id (.-dbId dataset)
         node (cond-> {}
                      view-id (assoc :view/id view-id)
                      db-id (assoc :db/id db-id))
         root? (= (.-isRoot dataset) "true")]
     (if root?
       child-path
       (get-element-path
         (.-parentElement element)
         (if (empty? node)
           child-path
           (conj child-path node)))))))



(def event-handlers (atom []))

(defn state-view []
  (let [ui-id (get-ui-id)]
    (r/create-class
      {:display-name "state-view"
       :reagent-render
       (fn [conn name state]
         [:div.state {:data-view-id ui-id}
          [:h1.state-name name]])})))

(defn statechart-view []
  (let [ui-id (get-ui-id)]
    (r/create-class
      {:display-name "statechart-view"
       :reagent-render
       (fn [conn e]
         (let [{machine :statechart/machine} @(p/pull conn '[*] e)
               states (:states machine)
               name (:id machine)]
           [:div.state {:data-view-id ui-id :data-db-id e :data-name "statechart"}
            [:h1.state-name name]
            [:div.state-states
             (for [[name state] (seq states)]
               ^{:key name} [state-view conn name state])]]))})))

(defn on-click [evt]
  (print (get-element-path (.-target evt))))

(defn app [conn]
  [:div {:data-is-root true
         :on-click on-click}
   [statechart-view conn 1]])

(defn init []
  (dom/render [app conn] (gdom/getElement "root")))




