(ns inspector.inspector
  (:require [posh.reagent :as p]
            [datascript.core :as d]
            [statecharts.core :as fsm]
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
  (let [{name       :inspector/name
         entity     :inspector/entity
         frameset   :inspector/frameset
         attributes :inspector/attributes} @(p/pull
                                              conn
                                              [:inspector/name
                                               :inspector/attributes
                                               :inspector/frameset
                                               {:inspector/entity '[*]}] e)
        entity-id (:db/id entity)

        matching-entities @(p/q (into [:find ['?e '...]
                                 :where] (for [attribute attributes]
                                           ['?e attribute '_])) conn)

        rest-entity (apply dissoc entity :db/id attributes)]

    [:div.inspector {:data-node "inspector" :data-db-id e}
     [:div.inspector-header
      [:h1.inspector-title name]

      (count matching-entities)]

     (for [attribute attributes]
       (let [value (get entity attribute)]
         ^{:key attribute}
         [:div.attribute
          [:div.attribute-name attribute]
          [:div.attribute-value
           (if (:_state value)
             [machine-view value (get-in @db/schema [attribute :machine])]
             [:div.literal-value (pr-str value)])]]))

     [:div.attribute
      [:div.attribute-name "view"]
      [:div.attribute-value [view-view entity-id frameset]]]

     (for [[key value] (seq rest-entity)]
       ^{:key key}
       [:div.attribute
        [:div.attribute-name key]
        [:div.attribute-value (pr-str value)]])]))

