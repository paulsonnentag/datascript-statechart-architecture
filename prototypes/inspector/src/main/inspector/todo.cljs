(ns inspector.todo
  (:require [posh.reagent :as p]
            [datascript.core :as d]
            [statecharts.core :as fsm]
            [inspector.events :as events]
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
    {:id      :view-mode-machine
     :context {}
     :initial :viewing
     :states
     {:viewing {:on
                {:edit :editing}}
      :editing {:entry (fn [_ {:keys [e]}]
                         (events/dispatch!
                           :enter
                           :todo/view-mode
                           [{:name :editing}]
                           {:todo {:db/id e :name :todo}}))
                :exit  (fn [_ {:keys [e]}]
                         (events/dispatch!
                           :exit
                           :todo/view-mode
                           [{:name :editing}]
                           {:todo {:db/id e :name :todo}}))
                :on
                {:save   {:target  :viewing
                          :actions (fn [_ {:keys [e] :as evt}]
                                     (events/dispatch!
                                       :save
                                       :todo/view-mode
                                       [{:name :editing}]
                                       {:todo {:db/id e :name :todo}
                                        :evt  evt}))}
                 :cancel {:target  :viewing
                          :actions (fn [_ {:keys [e] :as evt}]
                                     (events/dispatch!
                                       :cancel
                                       :todo/view-mode
                                       [{:name :editing}]
                                       {:todo {:db/id e :name :todo}
                                        :evt  evt}))}
                 :update {:actions (fn [_ {:keys [e] :as evt}]
                                     (events/dispatch!
                                       :update
                                       :todo/view-mode
                                       [{:name :editing}]
                                       {:todo {:db/id e :name :todo}
                                        :evt  evt}))}}}}}))

(db/register-machine! :todo/view-mode view-mode-machine)


; VIEW

(events/clear-selectors! :todo/view)

(events/add-selector!
  :todo/view :click [:checkbox]
  (fn [{:keys [todo]}]
    (events/trigger! (:db/id todo) :todo/completion :toggle)))

(events/add-selector!
  :todo/view :click [:label]
  (fn [{:keys [todo]}]
    (events/trigger! (:db/id todo) :todo/view-mode :edit)))

(events/add-selector!
  :todo/view :blur [:input]
  (fn [{:keys [todo]}]
    (events/trigger! (:db/id todo) :todo/view-mode :save)))

(events/add-selector!
  :todo/view :key-down [:input]
  (fn [{:keys [todo evt]}]
    (when (= "Escape" (.-code evt))
      (events/trigger! (:db/id todo) :todo/view-mode :cancel))))

(events/add-selector!
  :todo/view :change [:input]
  (fn [{:keys [todo evt]}]
    (events/trigger! (:db/id todo) :todo/view-mode :update {:value (input-value evt)})))


; VIEW-MODE


(events/clear-selectors! :todo/view-mode)

(events/add-selector!
  :todo/view-mode :enter [:editing]
  (fn [{:keys [todo]}]
    (let [todo-id (:db/id todo)
          {description :todo/description} (d/pull @conn [:todo/description] todo-id)]
      (p/transact! conn [[:db/add todo-id :todo/temp-description description]]))))

(events/add-selector!
  :todo/view-mode :save [:editing]
  (fn [{:keys [todo]}]
    (let [todo-id (:db/id todo)
          {temp-description :todo/temp-description} (d/pull @conn [:todo/temp-description] todo-id)]
      (p/transact! conn [[:db/add todo-id :todo/description temp-description]
                         [:db.fn/retractAttribute todo-id :todo/temp-description]]))))

(events/add-selector!
  :todo/view-mode :cancel [:editing]
  (fn [{:keys [todo]}]
    (let [todo-id (:db/id todo)]
      (p/transact! conn [[:db.fn/retractAttribute todo-id :todo/temp-description]]))))

(events/add-selector!
  :todo/view-mode :update [:editing]
  (fn [{:keys [todo evt]}]
    (let [new-description (:value evt)]
      (p/transact! conn [[:db/add (:db/id todo) :todo/temp-description new-description]]))))

(def base-frame-source "<div class=\"flex items-center gap-1 p-1\">
  <input #checkbox type=\"checkbox\">
  <div #description>{description}</div>
</div>")

(def base-example-source "{:todo/description \"Some task\"
 :todo/completion  {:_state :pending}
 :todo/view-mode   {:_state :viewing}}")

(def done-frame-source "<div class=\"flex items-center gap-1 p-1\">
  <input #checkbox type=\"checkbox\" checked>
  <div #description>{description}</div>
</div>")

(def done-example-source "{:todo/description \"Some task\"
 :todo/completion  {:_state :done}
 :todo/view-mode   {:_state :viewing}}")

(def editing-frame-source "<div class=\"flex items-center gap-1 p-1\">
    <input #checkbox type=\"checkbox\">
    <input class=\"\" #description-input value={temp-description}></div>
</div>")

(def editing-example-source "{:todo/description      \"Some task\"
 :todo/temp-description \"Some task\"
 :todo/completion       {:_state :pending}
 :todo/view-mode        {:_state :editing}}")

(def frameset
  {:example        {:todo/description "Some task"
                    :todo/completion  {:_state :pending}
                    :todo/view-mode   {:_state :viewing}}
   :frame-source   base-frame-source
   :example-source base-example-source
   :variations
   {:done    {:condition      (fn [e]
                                (let [{completion :todo/completion} (if (number? e)
                                                                      @(p/pull conn [:todo/completion] e))]
                                  (fsm/matches completion :done)))
              :example        {:todo/description "Some task"
                               :todo/completion  {:_state :done}
                               :todo/view-mode   {:_state :viewing}}
              :example-source done-example-source
              :frame-source   done-frame-source}
    :editing {:condition      (fn [e]
                                (let [{view-mode :todo/view-mode} (if (number? e)
                                                                    @(p/pull conn [:todo/view-mode] e))]
                                  (fsm/matches view-mode :editing)))
              :example        {:todo/description      "Some task"
                               :todo/temp-description "Some task"
                               :todo/completion       {:_state :pending}
                               :todo/view-mode        {:_state :editing}}
              :example-source editing-example-source
              :frame-source   editing-frame-source}}
   :view           (fn [e]
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
                       [:div.flex.gap-1.items-center.p-1 {:data-db-id e
                                                          :data-name  "todo"
                                                          :class      (when done? "is-done")}
                        [:input {:data-name "checkbox"
                                 :type      "checkbox"
                                 :checked   done?
                                 :on-change (fn [])}]
                        (if editing?
                          [:input.border.border-black
                           {:data-name "input"
                            :value     temp-description
                            :onChange  (fn [])
                            :ref       (fn [element]        ; TODO: handle this through event selectors
                                         #_(when (and element
                                                      (not in-frameset?))
                                             (.focus element)))}]
                          [:div {:data-name "label"} description])]))})