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
    [:div.flex.gap-4
     [:div
      {:on-click (fn [evt]
                   (.stopPropagation evt)
                   (on-select-path path))}
      [:div
       {:class (if active? "text-blue-500" "text-gray-500")}
       name]
      [:div.border.bg-white.shadow-sm.w-fit.child-pointer-events-none
       {:class (if selected? "border-blue-500")}
       (templates/render-frameset frameset conn example default-ns)]]

     (when-not (empty? variations)
       [:div.flex.flex-col.gap-2
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


(defn tab [{:keys [label selected? on-select]}]
  [:button.p-1.rounded-md
   {:class (when selected? "bg-gray-200")
    :on-click on-select}
   label])

(defn view-view []
  (let [selected-path! (r/atom [])
        selected-tab! (r/atom :events)
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
        [:div.flex.items-stretch.gap-4

         [:div.flex.flex-col.gap-4
          {:style {:min-width "100px"} }
          [:div.border.bg-white.shadow-sm.w-fit
           (templates/render-frameset frameset conn entity default-ns)]

          (when (and expanded? has-variations?)
            [frame-view {:current        entity
                         :default-ns     default-ns
                         :name           :base
                         :frameset       frameset
                         :selected-path  selected-path
                         :path           []
                         :on-select-path on-select-path}])]

         (when (and expanded?
                      (not (nil? selected-path)))
             (let [frame (lookup-path frameset selected-path)]
               [:div.flex-1.flex.flex-col.bg-white.shadow.rounded-md
                {:style {:min-height "500px"}}
                [:div.flex.gap-1.p-1.pb-0
                 [tab {:label "view" :selected? view-selected? :on-select #(reset! selected-tab! :view)}]
                 [tab {:label "example" :selected? example-selected? :on-select #(reset! selected-tab! :example)}]
                 [tab {:label "events" :selected? events-selected? :on-select #(reset! selected-tab! :events)}]]

                (cond
                  view-selected? [:textarea.bg-gray-50.border.border-gray-100.m-1.rounded-md.p-2.flex-1
                                  {:data-name "view-src"
                                   :value     (:frame-src frame)
                                   :on-change #(on-change-view-source (input-value %))}]
                  example-selected? [:pre.bg-gray-50.border.border-gray-100.m-1.rounded-md.p-2.flex-1
                                     (:example-src frame)]
                  events-selected? (let [{:keys [error value]} event-selectors-src]
                                     [:<>
                                      [:textarea.bg-gray-50.border.m-1.rounded-md.p-2.flex-1
                                       {:data-name "event-selectors-src"
                                        :class     (if error "border-red-500" "border-gray-100")
                                        :value     value
                                        :on-change #()}]
                                      [:div.p-1.pt-0.text-red-500
                                       (str (ex-message (ex-root-cause error)))]]))]))]))))


(defn state-view [state name state-def]
  (let [{substate-defs :states
         actions       :on} state-def
        active? (fsm/matches state name)]
    [:div.border.shadow.rounded-md.p-2.bg-white.w-fit
     {:class (if (and active? name) "border-blue-500" "border-gray-200")}

     (when name
       [:div.text-gray-500 name])

     (when-not (empty? actions)
       [:div.flex.flex-col.gap-1
        (for [[name [action]] (seq actions)]
          ^{:key name}
          [:div.flex.gap-1
           [:button.action-name
            (if active?
              {:class "text-blue-500" :data-name "action" :data-action name}
              {:disabled true})
            name]
           (when-let [target (:target action)]
             [:<>
              "→"
              [:div.action-target target]])])])

     (when-not (empty? substate-defs)
       [:div.flex.gap-2
        (for [[name substate-def] (seq substate-defs)]
          ^{:key name} [state-view state name substate-def])])]))

(defn machine-view [state machine expanded?]
  (if expanded?
    [state-view state nil machine]
    [:div.border.border-gray-200.shadow.rounded-md.p-1.bg-white (:_state state)]))

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
        expandable? (or state? frameset?)
        col-view? (or frameset?
                      (and state? expanded?))]
    [:div.flex
     {:class     [(if col-view? "flex-col gap-1" "flex-row items-center gap-2")
                  (when (and (not frameset?) (not expanded?)) "is-inline")]
      :data-name "attribute"
      :data-attr (full-name attr-name)}

     [:div.flex.items-center.gap-1.text-gray-500
      {:class (when-not expandable? "pl-5")}
      (when expandable?
        [:button.icon.icon-sm.icon-expandable.icon-gray
         {:class     (when expanded? "is-expanded")
          :data-name "expand-button"}])
      attr-name ":"]

     [:div
      {:class (when col-view? "pl-5")}
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

    [:div.bg-gray-100.rounded-md.shadow-sm
     {:data-name "inspector" :data-db-id e :data-selected-entity-id entity-id}
     [:div.p-3.flex.items-center.gap-2
      [:h1.text-xl name]

      (when (> (count matching-entities) 1)
        [:div.flex.gap-1 {:data-name "dot-selection"}
         (map-indexed
           (fn [index entity]
             ^{:key index}
             [:button.rounded-full
              {:data-idx  index
               :style {:width "10px" :height "10px"}
               :data-name "dot"
               :class     (if (= index selected-idx) "bg-gray-500" "bg-gray-300")}])
           matching-entities)])]


     [:div.p-3.flex.flex-col.gap-2
      [attribute-view e name entity-id view-attr frameset (contains? expanded-attributes view-attr)]

      (for [[index [attribute type]] (with-index (seq schema))]
        (let [value (get entity attribute)]
          (if (nil? type)
            ^{:key index} [attribute-type-picker e name schema attribute]
            ^{:key index} [attribute-view e name entity-id attribute value (contains? expanded-attributes attribute)])))

      (for [[key value] (seq rest-entity)]
        ^{:key key}
        [attribute-view e name entity-id key value (contains? expanded-attributes key)])

      [:button.w-min.whitespace-nowrap.gap-1.flex.items-center.text-gray-400
       {:data-name "add-attribute"
        :style     {:margin-left "-4px"}}
       [:div.icon.icon-plus.icon-gray] "new attribute"]]]))

