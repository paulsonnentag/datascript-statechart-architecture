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


(defn app [conn]
  [statechart-view conn 1])

(defn init []
  (dom/render [app conn] (gdom/getElement "root")))




