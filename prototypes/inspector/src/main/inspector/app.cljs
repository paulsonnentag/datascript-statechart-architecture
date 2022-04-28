(ns inspector.app
  (:require [statecharts.core :as fsm]
            [datascript.core :as d]
            [goog.dom :as gdom]
            [reagent.dom :as dom]
            [reagent.core :as r]
            [posh.reagent :as p]))

(declare trigger-stack!)

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

(def view-mode-machine
  (fsm/machine
    {:id      :completion-machine
     :context {}
     :initial :viewing
     :states
     {:viewing {:on
                {:edit :editing}}
      :editing {:entry (fn [_ {:keys [e]}]
                         (trigger-stack!
                         :enter
                         [{:node :todo :db/id e}
                          {:node :view-mode}
                          {:node :editing}]))
                :on
                {:save   {:target  :viewing
                          :actions (fn [_ {:keys [e value]}]
                                     (trigger-stack!
                                       :save
                                       [{:node :todo :db/id e}
                                        {:node :view-mode}
                                        {:node :editing}]
                                       value))}
                 :cancel {:target  :viewing
                          :actions (fn [_ {:keys [e value]}]
                                     (trigger-stack!
                                       :cancel
                                       [{:node :todo :db/id e}
                                        {:node :view-mode}
                                        {:node :editing}]
                                       value))}
                 :update {:actions (fn [_ {:keys [e value]}]
                                     (trigger-stack!
                                       :update
                                       [{:node :todo :db/id e}
                                        {:node :view-mode}
                                        {:node :editing}]
                                       value))}}}}}))

(def schema {:inspector/selected-entity {:db/type :db.type/ref}
             :todo/description          {}
             :todo/completion           {:machine completion-machine}
             :todo/view-mode            {:machine view-mode-machine}})

(defonce conn (d/create-conn schema))

(p/posh! conn)

(def inspector-id 1)
(def todo-id 2)

(d/transact! conn [{:db/id                     inspector-id
                    :inspector/selected-entity todo-id}
                   {:db/id            todo-id
                    :todo/description "Do something"
                    :todo/completion  {:_state :pending}
                    :todo/view-mode   {:_state :viewing}}])

; ui id

(def next-id! (atom 0))

(defn get-ui-id []
  (let [id @next-id!]
    (swap! next-id! inc)
    id))

; event selectors

(defn get-element-stack
  ([element]
   (get-element-stack element '()))

  ([element stack]
   (let [node (reduce
                (fn [node [str-key value]]
                  (let [key (keyword str-key)]
                    (case key
                      :dbId (if-let [id (js/parseInt value 10)]
                              (assoc node :db/id id)
                              node)
                      :node (assoc node :node (keyword value))

                      (assoc node key value))))
                {}
                (js/Object.entries (.-dataset element)))]
     (if (:isRoot node)
       stack
       (get-element-stack
         (.-parentElement element)
         (if (empty? node)
           stack
           (conj stack node)))))))


(def event-handlers! (atom (sorted-set-by #(-> % :selector count -))))

(defn trigger-stack!
  ([triggered-evt stack]
   (trigger-stack! triggered-evt stack nil))
  ([triggered-evt stack dom-evt]
  (loop [handlers @event-handlers!]
    (when-let [handler (first handlers)]
      (let [{:keys [cb selector evt]} handler
            continue-flag (when (= evt triggered-evt)
                            (loop [ctx {}
                                   stack stack
                                   selector selector]
                              (let [name (last selector)]
                                (if name
                                  (let [node (last stack)]
                                    (when node
                                      (if (= (:node node) name)
                                        (recur (assoc ctx name node)
                                               (drop-last stack)
                                               (drop-last selector))
                                        (recur ctx
                                               (drop-last stack)
                                               selector))))

                                  (cb ctx dom-evt)))))]
        (when (not (false? continue-flag))
          (recur (rest handlers))))))))

(defn trigger!
  ([e property type]
   (trigger! e property type {}))

  ([e property type data]
  (let [entity (d/pull @conn [property] e)
        machine (get-in schema [property :machine])]
    (assert machine "no statemachine defined")
    (if-let [state (get entity property)]
      (let [event (assoc data :e e :type type)
            new-state (fsm/transition machine state event)]
        (p/transact! conn [[:db/add e property new-state]]))
      (print "ignore event" entity e property type)))))

(defn on [evt selector cb]
  (swap!
    event-handlers!
    #(conj % {:evt      evt
              :selector selector
              :cb       cb})))

(defn trigger-dom-evt! [name evt]
  (let [stack (get-element-stack (.-target evt))]
    (trigger-stack! name stack evt)))

; API

; views

(on :click [:todo :checkbox]
    (fn [{:keys [todo]}]
      (trigger! (:db/id todo) :todo/completion :toggle)))

(on :click [:todo :label]
    (fn [{:keys [todo]}]
      (trigger! (:db/id todo) :todo/view-mode :edit)))

(on :blur [:todo :input]
    (fn [{:keys [todo]}]
      (trigger! (:db/id todo) :todo/view-mode :save)))

(on :key-down [:todo :input]
    (fn [{:keys [todo]} evt]
      (when (= "Escape" (.-code evt))
               (trigger! (:db/id todo) :todo/view-mode :cancel))))

(on :change [:todo :input]
    (fn [{:keys [todo]} evt]
      (trigger! (:db/id todo) :todo/view-mode :update {:value (input-value evt)})))

(on :enter [:todo :view-mode :editing]
    (fn [{:keys [todo]}]
      (let [todo-id (:db/id todo)
            {description :todo/description } (d/pull @conn [:todo/description] todo-id)]
        (p/transact! conn [[:db/add todo-id :todo/temp-description description]]))))

(on :save [:todo :view-mode :editing]
    (fn [{:keys [todo]}]
      (let [todo-id (:db/id todo)
            {temp-description :todo/temp-description } (d/pull @conn [:todo/temp-description] todo-id)]
        (p/transact! conn [[:db.fn/retractAttribute todo-id :todo/temp-description]
                           [:db/add todo-id :todo/description temp-description]]))))

(on :cancel [:todo :view-mode :editing]
    (fn [{:keys [todo]}]
      (let [todo-id (:db/id todo)
            {temp-description :todo/temp-description } (d/pull @conn [:todo/temp-description] todo-id)]
        (p/transact! conn [[:db.fn/retractAttribute todo-id :todo/temp-description]]))))

(on :update [:todo :view-mode :editing]
    (fn [{:keys [todo]} new-description]
      (p/transact! conn [[:db/add (:db/id todo) :todo/temp-description new-description]])))

(def todo-frameset
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
              :example   {:todo/description "Some task"
                          :todo/temp-description "Some task"
                          :todo/completion  {:_state :pending}
                          :todo/view-mode   {:_state :editing}}}}
   :view    (fn [e]
              (let [in-frameset? (not (number? e))
                    {completion  :todo/completion
                     view-mode   :todo/view-mode
                     description :todo/description
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
                          :type "checkbox"
                          :checked done?
                          :on-change (fn [])}]
                 (if editing?
                   [:input.todo-input {:data-node "input"
                            :value    temp-description
                            :onChange (fn [])
                            :ref      (fn [element]         ; TODO: handle this through event selectors
                                        (when (and element
                                                   (not in-frameset?))
                                          (.focus element)))}]
                   [:div {:data-node "label"} description])]))})

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
            {{selected-entity-id :db/id} :inspector/selected-entity} (d/pull @conn [:inspector/selected-entity] inspector-id)
            attribute-name (-> attribute :name keyword)
            action-name (-> action :name keyword)]
        (trigger! selected-entity-id attribute-name action-name))))


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

(defn inspector-view [e]
  (let [{todo :inspector/selected-entity} @(p/pull conn [{:inspector/selected-entity [:todo/completion
                                                                                      :todo/view-mode
                                                                                      :todo/description
                                                                                      :todo/temp-description]}] e)
        {description :todo/description
         temp-description :todo/temp-description
         completion  :todo/completion
         view-mode   :todo/view-mode
         todo-id     :db/id} todo]
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
      [:div.attribute-value [machine-view completion completion-machine]]]

     [:div.attribute {:data-node "attribute" :data-name "todo/view-mode"}
      [:div.attribute-name "view-mode"]
      [:div.attribute-value [machine-view view-mode view-mode-machine]]]

     [:div.attribute
      [:div.attribute-name "view"]
      [:div.attribute-value [view-view todo-id todo-frameset]]]]))

(defn app []
  [:div {:data-is-root true
         :on-click     #(trigger-dom-evt! :click %)
         :on-blur     #(trigger-dom-evt! :blur %)
         :on-key-down #(trigger-dom-evt! :key-down %)
         :on-change    #(trigger-dom-evt! :change %)}
   [inspector-view inspector-id]])

(defn ^:dev/after-load init []
  (dom/render [app] (gdom/getElement "root")))
