(ns inspector.inspector
  (:require [posh.reagent :as p]
            [reagent.core :as r]
            [datascript.core :as d]
            [statecharts.core :as fsm]
            [inspector.events :as events]
            [inspector.db :as db :refer [conn]]
            [inspector.helpers :refer [input-value with-index]]
            [inspector.templates :as templates]
            [inspector.editable-label :as editable-label]))

(events/clear-selectors! :inspector/view)

(events/add-selector!
  :inspector/view :click [:dot-selection :dot]
  (fn [{:keys [inspector dot]}]
    (let [inspector-id (:db/id inspector)
          dot-idx (-> dot :idx js/parseInt)]
      (p/transact! conn [[:db/add inspector-id :inspector/selected-index dot-idx]]))))

(events/add-selector!
  :inspector/view :click [:attribute :expand-button]
  (fn [{:keys [inspector attribute]}]
    (let [inspector-id (:db/id inspector)
          {expanded-attributes :inspector/expanded-attributes} (d/pull @conn [:inspector/expanded-attributes] inspector-id)
          attribute-name (-> attribute :attr keyword)
          new-expanded-attributes (if (contains? expanded-attributes attribute-name)
                                    (disj expanded-attributes attribute-name)
                                    (conj expanded-attributes attribute-name))]
      (p/transact! conn [[:db/add inspector-id :inspector/expanded-attributes new-expanded-attributes]]))))

(events/add-selector!
  :inspector/view :click [:attribute :action]
  (fn [{:keys [inspector attribute action]}]
    (let [selected-entity-id (-> inspector :selectedEntityId js/parseInt)
          attribute-name (-> attribute :attr keyword)
          action-name (-> action :action keyword)]
      (events/trigger! selected-entity-id attribute-name action-name))))

(events/add-selector!
  :inspector/view :change [:attribute :event-selectors-src]
  (fn [{:keys [attribute evt]}]
    (let [attr (-> attribute :attr keyword)
          new-src (input-value evt)]
      (events/update-event-selectors-src attr new-src))))

(defn unique-attribute-name [ns schema]
  (loop [i 0]
    (let [attribute (keyword
                      ns
                      (str "new-attribute"
                           (if (= i 0) "" (str "-" i))))]
      (if (contains? schema attribute)
        (recur (inc i))
        attribute))))

(events/add-selector!
  :inspector/view :click [:add-attribute]
  (fn [{:keys [inspector]}]
    (let [inspector-id (:db/id inspector)
          {schema :inspector/schema
           name   :inspector/name} (d/pull @conn [:inspector/schema
                                                  :inspector/name] inspector-id)
          attribute-name (unique-attribute-name name schema)
          new-schema (assoc schema attribute-name nil)]
      (p/transact! conn [[:db/add inspector-id :inspector/schema new-schema]]))))

(defn frame-view [{:keys [current default-ns name frameset selected-path path on-select-path]}]
  (let [{:keys [variations example condition]} frameset
        active? (or (nil? condition)
                    (condition current))
        in-selected-path? (= name (first selected-path))
        child-selected-path (if in-selected-path?
                              (rest selected-path)
                              [])
        selected? (and in-selected-path?
                       (= (count selected-path) 1))]
    [:div.frame-group
     [:div.frame-variation
      {:class    [(when active? "is-active")
                  (when selected? "is-selected")]
       :on-click (fn [evt]
                   (.stopPropagation evt)
                   (on-select-path path))}
      [:div.frame-name name]
      [:div.frame
       (templates/render-frameset frameset conn example default-ns)]]

     (when-not (empty? variations)
       [:div.frame-variations
        (for [[name variation] (seq variations)]
          ^{:key name}
          [frame-view {:current        current
                       :name           name
                       :default-ns     default-ns
                       :frameset       variation
                       :selected-path  child-selected-path
                       :path           (conj path name)
                       :on-select-path on-select-path}])])]))

(defn lookup-path [frameset path]
  (if (or (empty? path)
          (not= (first path) :base))
    nil
    (loop [frameset frameset
           subpath (rest path)]
      (if (empty? subpath)
        frameset
        (recur (get-in frameset [:variations (first subpath)])
               (rest subpath))))))

(defn ex-root-cause [ex]
  (if-let [cause-ex (ex-cause ex)]
    (ex-root-cause cause-ex)
    ex))

(defn view-view []
  (let [selected-path! (r/atom [:base])
        selected-tab! (r/atom :events)
        on-select-path #(reset! selected-path! %)]
    (fn [default-ns e event-selectors-src frameset expanded?]
      (let [view (:view frameset)
            selected-path @selected-path!
            selected-tab @selected-tab!
            view-selected? (= selected-tab :view)
            example-selected? (= selected-tab :example)
            events-selected? (= selected-tab :events)
            entity @(p/pull conn '[*] e)]
        [:div.with-source
         [:div.view-value
          [:div.frame
           (templates/render-frameset frameset conn entity default-ns)]

          (when expanded?
            [:<>
             [:div.view-value-divider]
             [frame-view {:view           view
                          :current        entity
                          :default-ns     default-ns
                          :name           :base
                          :frameset       frameset
                          :selected-path  selected-path
                          :path           [:base]
                          :on-select-path on-select-path}]])]

         (when (and expanded?
                    (not (empty? selected-path)))
           (let [frame (lookup-path frameset selected-path)]
             [:div.source
              [:div.source-tabs
               [:button.source-tab {:class    (when view-selected? "is-selected")
                                    :on-click #(reset! selected-tab! :view)} "view"]
               [:button.source-tab {:class    (when example-selected? "is-selected")
                                    :on-click #(reset! selected-tab! :example)} "example"]
               [:button.source-tab {:class    (when events-selected? "is-selected")
                                    :on-click #(reset! selected-tab! :events)} "events"]]
              (cond
                view-selected? [:pre.source-content
                                (:frame-src frame)]
                example-selected? [:pre.source-content
                                   (:example-src frame)]
                events-selected? (let [{:keys [error value]} event-selectors-src]
                                   [:<>
                                    [:textarea.source-content
                                     {:data-name "event-selectors-src"
                                      :class     (when error "has-errors")
                                      :value     value
                                      :on-change #()}]
                                    [:div.source-error
                                     (str (ex-message (ex-root-cause error)))]]))]))]))))


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
              {:class "is-active" :data-name "action" :data-action name}
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

(defn attribute-view [default-ns e name value expanded?]
  (let [state? (:_state value)
        frameset? (:frame value)
        expandable? (or state? frameset?)]
    [:tr.attribute
     {:class     [(when expandable? "is-expandable")
                  (when (and (not frameset?) (not expanded?)) "is-inline")]
      :data-name "attribute"
      :data-attr (full-name name)}

     [:th
      [:div.attribute-name
       (when expandable?
         [:button.attribute-expand-button
          {:class     (when expanded? "is-expanded")
           :data-name "expand-button"}])
       name]]

     [:td
      (cond
        state? [machine-view value (get-in @db/schema [name :machine]) expanded?]
        frameset? [view-view default-ns e (get-in @db/schema [name :evt-selectors-src]) value expanded?]
        :else [:div.attribute-literal-value (pr-str value)])]]))


(defn attribute-type-picker [e ns schema attribute]
  (let [attribute-name (name attribute)
        on-change (fn [new-attribute-name]
                    (let [type (get schema attribute)
                          new-attribute (keyword ns new-attribute-name)
                          new-schema (-> schema
                                         (dissoc attribute)
                                         (assoc new-attribute type))]

                      (p/transact! conn [[:db/add e :inspector/schema new-schema]])))]


    [:tr.attribute {}
     [:th
      [:div.attribute-name
       [editable-label/view attribute-name on-change]]]

     [:td "<not implemented>"]]))

(defn view [e]
  (let [{name                :inspector/name
         selected-idx        :inspector/selected-index
         frameset            :inspector/frameset
         schema              :inspector/schema
         expanded-attributes :inspector/expanded-attributes} @(p/pull
                                                                conn
                                                                [:inspector/name
                                                                 :inspector/schema
                                                                 :inspector/expanded-attributes
                                                                 :inspector/frameset
                                                                 :inspector/selected-index] e)

        attributes (->> schema
                        (remove (fn [[_ type]]
                                  (nil? type)))
                        (map first))

        matching-entities (if (not-empty attributes)
                            @(p/q (into [:find ['?e '...]
                                         :where] (for [attribute attributes]
                                                   ['?e attribute '_])) conn)
                            [])



        entity-id (nth matching-entities selected-idx (last matching-entities))

        entity @(p/pull conn '[*] entity-id)

        rest-entity (apply dissoc entity :db/id attributes)

        view-attr (keyword name "view")]

    [:div.inspector {:data-name "inspector" :data-db-id e :data-selected-entity-id entity-id}
     [:div.inspector-header
      [:h1.inspector-title name]

      (when (> (count matching-entities) 1)
        [:div.dot-selection {:data-name "dot-selection"}
         (map-indexed
           (fn [index entity]
             ^{:key index}
             [:button.dot
              {:data-idx  index
               :data-name "dot"
               :class     (when (= index selected-idx) "is-selected")}])
           matching-entities)])]

     [:table.attributes
      [:tbody
       [attribute-view name entity-id view-attr frameset (contains? expanded-attributes view-attr)]

       (for [[index [attribute type]] (with-index (seq schema))]
         (let [value (get entity attribute)]
           (if (nil? type)
             ^{:key index} [attribute-type-picker e name schema attribute]
             ^{:key index} [attribute-view name entity-id attribute value (contains? expanded-attributes attribute)])))

       (for [[key value] (seq rest-entity)]
         ^{:key key}
         [attribute-view name entity-id key value (contains? expanded-attributes key)])]]


     [:button.bg-gray-200 {:data-name "add-attribute"} "add attribute"]]))

