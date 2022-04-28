(ns inspector.app
  (:require [statecharts.core :as fsm]
            [datascript.core :as d]
            [goog.dom :as gdom]
            [reagent.dom :as dom]
            [reagent.core :as r]
            [posh.reagent :as p]))

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


(def schema {:inspector/selected-entity {:db/type :db.type/ref}
             :todo/description          {}
             :todo/completion           {:machine completion-machine}})

(defonce conn (d/create-conn schema))

(p/posh! conn)

(def inspector-id 1)
(def todo-id 2)

(d/transact! conn [{:db/id                     inspector-id
                    :inspector/selected-entity todo-id}
                   {:db/id            todo-id
                    :todo/description "Do something"
                    :todo/completion  {:_state :pending}}])

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
                      :element (assoc node :element (keyword value))

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

(defn trigger-dom-evt! [triggered-evt stack]
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
                                      (if (= (:element node) name)
                                        (recur (assoc ctx name node)
                                               (drop-last stack)
                                               (drop-last selector))
                                        (recur ctx
                                               (drop-last stack)
                                               selector))))

                                  (cb ctx)))))]
        (when (not (false? continue-flag))
          (recur (rest handlers)))))))

(defn trigger! [e property type]
  (let [entity (d/pull @conn [property] e)
        machine (get-in schema [property :machine])]
    (assert machine "no statemachine defined")
    (if-let [state (get entity property)]
      (let [event {:e e :type type}
            new-state (fsm/transition machine state event)]
        (p/transact! conn [[:db/add e property new-state]]))
      (print "ignore event" entity e property type))))

(defn on [evt selector cb]
  (swap!
    event-handlers!
    #(conj % {:evt      evt
              :selector selector
              :cb       cb})))

(defn trigger-click! [evt]
  (let [stack (get-element-stack (.-target evt))]
    (trigger-dom-evt! :click stack)))

; API

; views

(def todo-frameset
  {:example {:todo/description "Some task"
             :todo/completion  {:_state :pending}}
   :variations
   {:done {:source    '[..]
           :condition (fn [e]
                        (let [{completion :todo/completion} (if (number? e)
                                                              @(p/pull conn [:todo/completion] e))]
                          (fsm/matches completion :done)))
           :example   {:todo/description "Some task"
                       :todo/completion  {:_state :done}}}}
   :view    (fn [e]
              (let [{completion  :todo/completion
                     description :todo/description} (if (number? e)
                                                      @(p/pull conn [:todo/completion :todo/description] e)
                                                      e)
                    done? (fsm/matches completion :done)]
                [:div.todo
                 [:input {:type "checkbox" :checked done? :readOnly true}]
                 [:div description]]))})

(defn replace-entity-with-example! [e example]
  (let [retract-keys (-> (d/pull @conn '[*] e)
                         (dissoc :db/id)
                         keys)
        retract-tx  (for [key retract-keys]
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
      [:div.frame
       [view e]]]
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
              {:class "is-active" :data-element "action" :data-name name}
              {:disabled true})
            name]
           "→"
           [:div.action-target (:target action)]])])

     (when-not (empty? substate-defs)
       [:div.state-substates
        (for [[name substate-def] (seq substate-defs)]
          ^{:key name} [state-view state name substate-def])])]))

(defn machine-view [state machine]
  [state-view state nil machine])

(defn inspector-view [e]
  (let [{todo :inspector/selected-entity} @(p/pull conn [{:inspector/selected-entity [:todo/completion
                                                                                      :todo/description]}] e)
        {description :todo/description
         completion  :todo/completion
         todo-id     :db/id} todo]

    [:div.inspector {:data-element "inspector" :data-db-id e}
     [:h1 "Todo"]
     [:div.attribute.is-inline
      [:div.attribute-name "description"]
      [:div.attribute-value (pr-str description)]]

     [:div.attribute {:data-element "attribute" :data-name "todo/completion"}
      [:div.attribute-name "completion"]
      [:div.attribute-value [machine-view completion completion-machine]]]

     [:div.attribute
      [:div.attribute-name "view"]
      [:div.attribute-value [view-view todo-id todo-frameset]]]]))

(defn app []
  [:div {:data-is-root true
         :on-click     trigger-click!}
   [inspector-view inspector-id]])

(defn ^:dev/after-load init []
  (dom/render [app] (gdom/getElement "root")))




