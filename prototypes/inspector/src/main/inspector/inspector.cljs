(ns inspector.inspector
  (:require [posh.reagent :as p]
            [datascript.core :as d]
            [statecharts.core :as fsm]
            [inspector.todo :as todo]
            [inspector.events :as events :refer [on]]
            [inspector.db :as db :refer [conn]]))

(defn replace-entity-with-example! [e example]
  (let [retract-keys (-> (d/pull @conn '[*] e)
                         (dissoc :db/id)
                         keys)
        retract-tx (for [key retract-keys]
                     [:db.fn/retractAttribute key])
        add-tx (for [[key value] (seq example)]
                 [:db/add e key value])]

    (p/transact! conn (concat retract-tx add-tx))))

(defn frame-view [view current name frame]
  (let [{:keys [variations example condition]} frame
        active? (or (nil? condition)
                    (condition current))]
    [:div.frame-group
     [:div.frame-variation {:class (when active? "is-active")}
      [:div.frame-name name]
      [:div.frame {:on-click #(replace-entity-with-example! current example)} ; TODO: replace with event selector
       [view example]]]

     (when-not (empty? variations)
       [:div.frame-variations
        (for [[name variation] (seq variations)]
          ^{:key name}
          [frame-view view current name variation])])]))

(defn view-view [e frameset]
  (let [view (:view frameset)]
    [:div.view-value
     [:div.view-value-preview
      [view e]]
     [:div.view-value-frames
      [frame-view view e "base" frameset]]]))



(on :click [:inspector :attribute :action]
    (fn [{:keys [inspector attribute action]}]
      (let [inspector-id (:db/id inspector)
            {{selected-entity-id :db/id} :inspector/entity} (d/pull @conn [:inspector/entity] inspector-id)
            attribute-name (-> attribute :name keyword)
            action-name (-> action :name keyword)]
        (events/trigger! selected-entity-id attribute-name action-name))))


(defn state-view [state name state-def]
  (let [{substate-defs :states
         actions       :on} state-def
        active? (fsm/matches state name)]
    [:div.state {:class (when (and name active?) "is-active")}

     (when name
       [:div.state-name name])

     (when-not (empty? actions)
       [:div.state-actions
        (for [[name [action]] (seq actions)]
          ^{:key name}
          [:div.action
           [:button.action-name
            (if active?
              {:class "is-active" :data-node "action" :data-name name}
              {:disabled true})
            name]
           (when-let [target (:target action)]
             [:<>
              "→"
              [:div.action-target target]])])])

     (when-not (empty? substate-defs)
       [:div.state-substates
        (for [[name substate-def] (seq substate-defs)]
          ^{:key name} [state-view state name substate-def])])]))

(defn machine-view [state machine]
  [state-view state nil machine])

(defn view [e]
  (let [{todo :inspector/entity} @(p/pull conn [{:inspector/entity [:todo/completion
                                                                    :todo/view-mode
                                                                    :todo/description
                                                                    :todo/temp-description]}] e)
        {description      :todo/description
         temp-description :todo/temp-description
         completion       :todo/completion
         view-mode        :todo/view-mode
         todo-id          :db/id} todo]
    [:div.inspector {:data-node "inspector" :data-db-id e}
     [:h1 "Todo"]
     [:div.attribute.is-inline
      [:div.attribute-name "description"]
      [:div.attribute-value (pr-str description)]]

     (when temp-description
       [:div.attribute.is-inline
        [:div.attribute-name "temp-description"]
        [:div.attribute-value (pr-str temp-description)]])

     [:div.attribute {:data-node "attribute" :data-name "todo/completion"}
      [:div.attribute-name "completion"]
      [:div.attribute-value [machine-view completion todo/completion-machine]]]

     [:div.attribute {:data-node "attribute" :data-name "todo/view-mode"}
      [:div.attribute-name "view-mode"]
      [:div.attribute-value [machine-view view-mode todo/view-mode-machine]]]

     [:div.attribute
      [:div.attribute-name "view"]
      [:div.attribute-value [view-view todo-id todo/frameset]]]]))