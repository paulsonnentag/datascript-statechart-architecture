(ns inspector.todo
  (:require [posh.reagent :as p]
            [datascript.core :as d]
            [statecharts.core :as fsm]
            [inspector.events :as events :refer [on]]
            [inspector.db :as db :refer [conn]]
            [inspector.helpers :refer [input-value]]))

(def completion-machine
  (fsm/machine
    {:id      :completion-machine
     :initial :pending
     :states
     {:pending {:on
                {:toggle :done}}
      :done    {:on
                {:toggle :pending}}}}))

(db/register-machine! :todo/completion completion-machine)

(def view-mode-machine
  (fsm/machine
    {:id      :completion-machine
     :context {}
     :initial :viewing
     :states
     {:viewing {:on
                {:edit :editing}}
      :editing {:entry (fn [_ {:keys [e]}]
                         (events/trigger-stack!
                           :enter
                           [{:node :todo :db/id e}
                            {:node :view-mode}
                            {:node :editing}]))
                :on
                {:save   {:target  :viewing
                          :actions (fn [_ {:keys [e value]}]
                                     (events/trigger-stack!
                                       :save
                                       [{:node :todo :db/id e}
                                        {:node :view-mode}
                                        {:node :editing}]
                                       value))}
                 :cancel {:target  :viewing
                          :actions (fn [_ {:keys [e value]}]
                                     (events/trigger-stack!
                                       :cancel
                                       [{:node :todo :db/id e}
                                        {:node :view-mode}
                                        {:node :editing}]
                                       value))}
                 :update {:actions (fn [_ {:keys [e value]}]
                                     (events/trigger-stack!
                                       :update
                                       [{:node :todo :db/id e}
                                        {:node :view-mode}
                                        {:node :editing}]
                                       value))}}}}}))

(db/register-machine! :todo/view-mode view-mode-machine)

(on :click [:todo :checkbox]
    (fn [{:keys [todo]}]
      (events/trigger! (:db/id todo) :todo/completion :toggle)))

(on :click [:todo :label]
    (fn [{:keys [todo]}]
      (events/trigger! (:db/id todo) :todo/view-mode :edit)))

(on :blur [:todo :input]
    (fn [{:keys [todo]}]
      (events/trigger! (:db/id todo) :todo/view-mode :save)))

(on :key-down [:todo :input]
    (fn [{:keys [todo]} evt]
      (when (= "Escape" (.-code evt))
        (events/trigger! (:db/id todo) :todo/view-mode :cancel))))

(on :change [:todo :input]
    (fn [{:keys [todo]} evt]
      (events/trigger! (:db/id todo) :todo/view-mode :update {:value (input-value evt)})))

(on :enter [:todo :view-mode :editing]
    (fn [{:keys [todo]}]
      (let [todo-id (:db/id todo)
            {description :todo/description} (d/pull @conn [:todo/description] todo-id)]
        (p/transact! conn [[:db/add todo-id :todo/temp-description description]]))))

(on :save [:todo :view-mode :editing]
    (fn [{:keys [todo]}]
      (let [todo-id (:db/id todo)
            {temp-description :todo/temp-description} (d/pull @conn [:todo/temp-description] todo-id)]
        (p/transact! conn [[:db.fn/retractAttribute todo-id :todo/temp-description]
                           [:db/add todo-id :todo/description temp-description]]))))

(on :cancel [:todo :view-mode :editing]
    (fn [{:keys [todo]}]
      (let [todo-id (:db/id todo)
            {temp-description :todo/temp-description} (d/pull @conn [:todo/temp-description] todo-id)]
        (p/transact! conn [[:db.fn/retractAttribute todo-id :todo/temp-description]]))))

(on :update [:todo :view-mode :editing]
    (fn [{:keys [todo]} new-description]
      (p/transact! conn [[:db/add (:db/id todo) :todo/temp-description new-description]])))

(def frameset
  {:example {:todo/description "Some task"
             :todo/completion  {:_state :pending}
             :todo/view-mode   {:_state :viewing}}
   :variations
   {:done    {:source    '[..]
              :condition (fn [e]
                           (let [{completion :todo/completion} (if (number? e)
                                                                 @(p/pull conn [:todo/completion] e))]
                             (fsm/matches completion :done)))
              :example   {:todo/description "Some task"
                          :todo/completion  {:_state :done}
                          :todo/view-mode   {:_state :viewing}}}
    :editing {:source    '[..]
              :condition (fn [e]
                           (let [{view-mode :todo/view-mode} (if (number? e)
                                                               @(p/pull conn [:todo/view-mode] e))]
                             (fsm/matches view-mode :editing)))
              :example   {:todo/description      "Some task"
                          :todo/temp-description "Some task"
                          :todo/completion       {:_state :pending}
                          :todo/view-mode        {:_state :editing}}}}
   :view    (fn [e]
              (let [in-frameset? (not (number? e))
                    {completion       :todo/completion
                     view-mode        :todo/view-mode
                     description      :todo/description
                     temp-description :todo/temp-description} (if in-frameset?
                                                                e
                                                                @(p/pull conn [:todo/completion
                                                                               :todo/view-mode
                                                                               :todo/description
                                                                               :todo/temp-description] e))
                    done? (fsm/matches completion :done)
                    editing? (fsm/matches view-mode :editing)]
                [:div.todo {:data-db-id e :data-node "todo"}
                 [:input {:data-node "checkbox"
                          :type      "checkbox"
                          :checked   done?
                          :on-change (fn [])}]
                 (if editing?
                   [:input.todo-input {:data-node "input"
                                       :value     temp-description
                                       :onChange  (fn [])
                                       :ref       (fn [element] ; TODO: handle this through event selectors
                                                    #_(when (and element
                                                               (not in-frameset?))
                                                      (.focus element)))}]
                   [:div {:data-node "label"} description])]))})