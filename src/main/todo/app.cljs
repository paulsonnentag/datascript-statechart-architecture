(ns todo.app
  (:require [statecharts.core :as fsm]
            [datascript.core :as d]
            [goog.dom :as gdom]
            [reagent.dom :as dom]
            [posh.reagent :as p]
            [react :as r]))

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

(defn entry-editing [state {:keys [e conn]}]
  (let [{description :todo/description} (d/pull @conn [:todo/description] e)]
    (assoc state :temp-description description)))

(defn editing-update [state {value :value}]
  (assoc state :temp-description value))

(defn editing-cancel [state]
  (dissoc state :temp-description))

(defn editing-save [{description :temp-description :as state} {:keys [e conn]}]
  (d/transact! conn [[:db/add e :todo/description description]])
  (dissoc state :temp-description))

(def view-mode-machine
  (fsm/machine
    {:id      :completion-machine
     :context {}
     :initial :viewing
     :states
     {:viewing {:on
                {:edit :editing}}
      :editing {:entry (fsm/assign entry-editing)
                :on
                {:save   {:target :viewing
                          :actions (fsm/assign editing-save)}
                 :cancel {:target :viewing
                          :actions (fsm/assign editing-cancel)}
                 :update {:actions (fsm/assign editing-update)}}}}}))

(def schema {:todo/completion  {:machine completion-machine}
             :todo/view-mode   {:machine view-mode-machine}
             :todo/description {}})

(defonce conn (d/create-conn schema))

(p/posh! conn)

(d/transact! conn [{:db/id            1
                    :todo/completion  {:_state :done}
                    :todo/view-mode   {:_state :viewing}
                    :todo/description "do something"}
                   {:db/id            2
                    :todo/completion  {:_state :pending}
                    :todo/view-mode   {:_state :viewing}
                    :todo/description "do something else"}])

(defn transact-event! [conn e property event]
  (let [entity (d/pull @conn [property] e)
        state (get entity property)
        machine (get-in schema [property :machine])
        event (assoc event :e e :conn conn)
        new-state (fsm/transition machine state event)]
    (assert machine)
    (p/transact! conn [[:db/add e property new-state]])))

(defn toggle-todo-completion [conn e]
  (transact-event! conn e :todo/completion {:type :toggle}))

(defn edit-todo [conn e]
  (transact-event! conn e :todo/view-mode {:type :edit}))

(defn save-edit-todo [conn e]
  (transact-event! conn e :todo/view-mode {:type :save}))

(defn update-edit-todo [conn e value]
  (transact-event! conn e :todo/view-mode {:type :update :value value}))

(defn cancel-edit-todo [conn e]
  (transact-event! conn e :todo/view-mode {:type :cancel}))

(defn on-submit-handler [conn e evt]
  (.preventDefault evt)
  (save-edit-todo conn e))

(defn on-key-press-handler [conn e evt]
  (when (= "Escape" (.-code evt))
    (cancel-edit-todo conn e)))

(defn todo-view [conn e]
  (let [{completion  :todo/completion
         view-mode   :todo/view-mode
         description :todo/description} @(p/pull conn '[*] e)
        done? (fsm/matches completion :done)]
    [:div.Todo {:class (when done? "isDone")}
     [:input {:type     "checkbox"
              :read-only true
              :checked  done?
              :on-change #(toggle-todo-completion conn e)}]

     (if (fsm/matches view-mode :editing)
       [:form {:onSubmit #(on-submit-handler conn e %)}
        [:input {:value  (:temp-description view-mode)
                 :on-blur #(save-edit-todo conn e)
                 :on-key-down #(on-key-press-handler conn e %)
                 :ref #(when %
                         (.focus %))
                 :on-change #(update-edit-todo conn e (input-value %))}]]
       [:span {:on-click #(edit-todo conn e) } description])]))


(defn app [conn]
  (let [todos @(p/q '[:find [?id ...]
                      :where [?id :todo/description _]] conn)]
    [:div.Todos
     [:h1 "Todos"]
     (for [e todos]
       ^{:key e} [todo-view conn e])]))


(defn init []
  (dom/render [app conn] (gdom/getElement "root")))




