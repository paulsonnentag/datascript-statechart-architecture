(ns inspector.inspector
  (:require [posh.reagent :as p]
            [reagent.core :as r]
            [datascript.core :as d]
            [statecharts.core :as fsm]
            [inspector.events :as events]
            [inspector.compiler :as compiler]
            [inspector.db :as db :refer [conn]]
            [inspector.helpers :refer [input-value with-index ex-root-cause]]
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

        selected? (and (empty? selected-path)
                       (sequential? selected-path))

        selected-child (first selected-path)]
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
                       :selected-path  (if (= name selected-child)
                                         (rest selected-path)
                                         nil)
                       :path           (conj path name)
                       :on-select-path on-select-path}])])]))

(defn lookup-path [frameset path]
  (if (empty? path)
    frameset
    (lookup-path
      (get-in frameset [:variations (first path)])
      (rest path))))

(defn update-in-path [frameset path fn]
  (if (empty? path)
    (fn frameset)
    (let [[name & rest] path]
      (update-in frameset [:variations name] #(update-in-path % rest fn)))))


(defn view-view []
  (let [selected-path! (r/atom [])
        selected-tab! (r/atom :view)
        on-select-path #(reset! selected-path! %)]
    (fn [inspector-id default-ns e event-selectors-src frameset expanded?]
      (let [selected-path @selected-path!
            selected-tab @selected-tab!
            view-selected? (= selected-tab :view)
            example-selected? (= selected-tab :example)
            events-selected? (= selected-tab :events)
            has-variations? (:variations frameset)
            entity @(p/pull conn '[*] e)
            on-change-view-source (fn [source]
                                    (let [new-frameset (-> frameset
                                                           (update-in-path selected-path #(assoc % :frame-src source))
                                                           (templates/create-frameset))]

                                      (p/transact! conn [[:db/add inspector-id :inspector/frameset new-frameset]])))]
        [:div.with-source
         [:div.view-value
          [:div.frame
           (templates/render-frameset frameset conn entity default-ns)]

          (when (and expanded? has-variations?)
            [:<>
             [:div.view-value-divider]
             [frame-view {:current        entity
                          :default-ns     default-ns
                          :name           :base
                          :frameset       frameset
                          :selected-path  selected-path
                          :path           []
                          :on-select-path on-select-path}]])]

         (when (and expanded?
                    (not (nil? selected-path)))
           (let [frame (lookup-path frameset selected-path)]
             [:div.source
              [:div.source-tabs
               [:button.source-tab {:class    (when view-selected? "is-selected")
                                    :on-click #(reset! selected-tab! :view)} "view"]
               (when has-variations?
                 [:button.source-tab {:class    (when example-selected? "is-selected")
                                      :on-click #(reset! selected-tab! :example)} "example"])
               [:button.source-tab {:class    (when events-selected? "is-selected")
                                    :on-click #(reset! selected-tab! :events)} "events"]]
              (cond
                view-selected? [:textarea.source-content
                                {:data-name "view-src"
                                 :value     (:frame-src frame)
                                 :on-change #(on-change-view-source (input-value %))}]
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

(defn attribute-literal-input []
  (let [!source (r/atom nil)
        !result (r/atom nil)]
    (fn [e attr value]
      (let [on-change-input (fn [evt]
                              (let [new-source (input-value evt)]
                                (reset! !source new-source)
                                (compiler/eval-src
                                  new-source
                                  :expr
                                  (fn [result]
                                    (reset! !result result)))))
            on-blur #(let [{error     :error
                            new-value :value} @!result]
                       (when-not error
                         (when-not (and (nil? e) (nil? new-value))
                           (p/transact! conn [(cond
                                                (nil? e) (assoc {} attr new-value)
                                                (nil? new-value) [:db.fn/retractAttribute e attr]
                                                :else [:db/add e attr new-value])]))
                         (reset! !source nil)
                         (reset! !result nil)))

            on-click #(let [source (pr-str value)]
                        (reset!
                          !source
                          (if (= source "nil") "" source)))]
        (let [source @!source
              error? (-> @!result :error)]
          (if source
            [:div
             [:input {:ref       #(when % (.focus %))
                      :class     (when error? "bg-red-200")
                      :value     source
                      :on-change on-change-input
                      :on-blur   on-blur}]]


            [:div {:on-click on-click} (pr-str value)]))))))

(defn attribute-view [inspector-id default-ns e attr-name value expanded?]
  (let [state? (:_state value)
        frameset? (= (name attr-name) "view")
        expandable? (or state? frameset?)]
    [:tr.attribute
     {:class     [(when expandable? "is-expandable")
                  (when (and (not frameset?) (not expanded?)) "is-inline")]
      :data-name "attribute"
      :data-attr (full-name attr-name)}

     [:th
      [:div.attribute-name
       (when expandable?
         [:button.attribute-expand-button
          {:class     (when expanded? "is-expanded")
           :data-name "expand-button"}])
       attr-name]]

     [:td
      (cond
        state? [machine-view value (get-in @db/schema [attr-name :machine]) expanded?]
        frameset? [view-view inspector-id default-ns e (get-in @db/schema [attr-name :evt-selectors-src]) value expanded?]
        :else [attribute-literal-input e attr-name value])]]))


(defn attribute-type-picker [e ns schema attribute]
  (let [attribute-name (name attribute)
        on-change-attribute-name (fn [new-attribute-name]
                                   (let [type (get schema attribute)
                                         new-attribute (keyword ns new-attribute-name)
                                         new-schema (-> schema
                                                        (dissoc attribute)
                                                        (assoc new-attribute type))]

                                     (p/transact! conn [[:db/add e :inspector/schema new-schema]])))

        on-change-type (fn [evt]
                         (let [new-type (-> evt input-value keyword)
                               new-schema (assoc schema attribute new-type)]
                           (p/transact! conn [[:db/add e :inspector/schema new-schema]])))]


    [:tr.attribute {}
     [:th
      [:div.attribute-name
       [editable-label/view attribute-name on-change-attribute-name]]]

     [:td [:select {:on-change on-change-type}
           [:option ""]
           [:option "literal"]]]]))

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
                            (->> attributes
                                 (mapcat
                                   (fn [attribute]
                                     @(p/q [:find ['?e '...]
                                            :where ['?e attribute '_]] conn)))

                                 (distinct)
                                 (sort))
                            [])

        entity-id (nth matching-entities selected-idx (last matching-entities))

        entity (when entity-id
                 @(p/pull conn '[*] entity-id))

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
       [attribute-view e name entity-id view-attr frameset (contains? expanded-attributes view-attr)]

       (for [[index [attribute type]] (with-index (seq schema))]
         (let [value (get entity attribute)]
           (if (nil? type)
             ^{:key index} [attribute-type-picker e name schema attribute]
             ^{:key index} [attribute-view e name entity-id attribute value (contains? expanded-attributes attribute)])))

       (for [[key value] (seq rest-entity)]
         ^{:key key}
         [attribute-view e name entity-id key value (contains? expanded-attributes key)])]]


     [:button.bg-gray-200 {:data-name "add-attribute"} "add attribute"]]))

