(ns inspector.inspector
  (:require [posh.reagent :as p]
            [datascript.core :as d]
            [statecharts.core :as fsm]
            [inspector.events :as events :refer [on]]
            [inspector.db :as db :refer [conn]]))

(defn frame-view [view current name frame]
  (let [{:keys [variations example condition]} frame
        active? (or (nil? condition)
                    (condition current))]
    [:div.frame-group
     [:div.frame-variation {:class (when active? "is-active")}
      [:div.frame-name name]
      [:div.frame
       [view example]]]

     (when-not (empty? variations)
       [:div.frame-variations
        (for [[name variation] (seq variations)]
          ^{:key name}
          [frame-view view current name variation])])]))

(defn view-view [e frameset expanded?]
  (let [view (:view frameset)]
    [:div.view-value
     [:div.frame
      [view e]]

      (when expanded?
        [:<>
        [:div.view-value-divider]
        [frame-view view e "base" frameset]])]))

(on :click [:inspector :attribute :action]
    (fn [{:keys [inspector attribute action]}]
      (let [selected-entity-id (-> inspector :selectedEntityId js/parseInt)
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

(defn machine-view [state machine expanded?]
  (if expanded?
    [state-view state nil machine]
    [:div.state (:_state state)]))

(defn full-name [keyword]
  (subs (str keyword) 1))

(defn attribute-view [e name value expanded?]
  (let [state? (:_state value)
        frameset? (fn? (:view value))
        expandable? (or state? frameset?)]

    [:tr.attribute
     {:class     [(when expandable? "is-expandable")
                  (when (and (not frameset?) (not expanded?)) "is-inline")]
      :data-node "attribute"
      :data-name (full-name name)}

     [:th
      [:div.attribute-name
       (when expandable?
         [:button.attribute-expand-button
          {:class     (when expanded? "is-expanded")
           :data-node "expand-button"}])
       name]]

     [:td
      (cond
        state? [machine-view value (get-in @db/schema [name :machine]) expanded?]
        frameset? [view-view e value expanded?]
        :else [:div.attribute-literal-value (pr-str value)])]]))

(on :click [:inspector :dot-selection :dot]
    (fn [{:keys [inspector dot]}]
      (let [inspector-id (:db/id inspector)
            dot-idx (-> dot :idx js/parseInt)]
        (p/transact! conn [[:db/add inspector-id :inspector/selected-index dot-idx]]))))

(on :click [:inspector :attribute :expand-button]
    (fn [{:keys [inspector attribute]}]
      (let [inspector-id (:db/id inspector)
            {expanded-attributes :inspector/expanded-attributes} (d/pull @conn [:inspector/expanded-attributes] inspector-id)
            attribute-name (-> attribute :name keyword)
            new-expanded-attributes (if (contains? expanded-attributes attribute-name)
                                      (disj expanded-attributes attribute-name)
                                      (conj expanded-attributes attribute-name))]
        (p/transact! conn [[:db/add inspector-id :inspector/expanded-attributes new-expanded-attributes]]))))

(defn view [e]
  (let [{name                :inspector/name
         selected-idx        :inspector/selected-index
         frameset            :inspector/frameset
         attributes          :inspector/attributes
         expanded-attributes :inspector/expanded-attributes} @(p/pull
                                                                conn
                                                                [:inspector/name
                                                                 :inspector/attributes
                                                                 :inspector/expanded-attributes
                                                                 :inspector/frameset
                                                                 :inspector/selected-index] e)

        matching-entities @(p/q (into [:find ['?e '...]
                                       :where] (for [attribute attributes]
                                                 ['?e attribute '_])) conn)

        entity-id (nth matching-entities selected-idx (last matching-entities))

        entity @(p/pull conn '[*] entity-id)

        rest-entity (apply dissoc entity :db/id attributes)]

    [:div.inspector {:data-node "inspector" :data-db-id e :data-selected-entity-id entity-id}
     [:div.inspector-header
      [:h1.inspector-title name]

      (when (> (count matching-entities) 1)
        [:div.dot-selection {:data-node "dot-selection"}
         (map-indexed
           (fn [idx entity]
             ^{:key idx}
             [:button.dot
              {:data-idx  idx
               :data-node "dot"
               :class     (when (= idx selected-idx) "is-selected")}])
           matching-entities)])]

     [:table.attributes
      [:tbody
       (for [attribute attributes]
         (let [value (get entity attribute)]
           ^{:key attribute}
           [attribute-view entity-id attribute value (contains? expanded-attributes attribute)]))

       (for [[key value] (seq rest-entity)]
         ^{:key key}
         [attribute-view entity-id key value (contains? expanded-attributes key)])

       [attribute-view entity-id :view frameset (contains? expanded-attributes :view)]]]]))

