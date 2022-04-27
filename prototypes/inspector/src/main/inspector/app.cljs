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
             :todo/completion           {}})

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

  ([^js element stack]
   (let [dataset (.-dataset element)
         view-id (-> dataset .-viewId js/parseInt)
         db-id (-> dataset .-dbId js/parseInt)
         name (-> dataset .-name keyword)
         node (cond-> {}
                      name (assoc :name name)
                      (not (js/isNaN view-id)) (assoc :view/id view-id)
                      (not (js/isNaN db-id)) (assoc :db/id db-id))
         root? (= (.-isRoot dataset) "true")]
     (if root?
       stack
       (get-element-stack
         (.-parentElement element)
         (if (empty? node)
           stack
           (conj stack node)))))))


(def event-handlers! (atom (sorted-set-by #(-> % :selector count -))))

(defn trigger! [triggered-evt stack]
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
                                      (if (= (:name node) name)
                                        (recur (assoc ctx name node)
                                               (drop-last stack)
                                               (drop-last selector))
                                        (recur ctx
                                               (drop-last stack)
                                               selector))))

                                  (cb ctx)))))]
        (when (not (false? continue-flag))
          (recur (rest handlers)))))))

(defn on [evt selector cb]
  (swap!
    event-handlers!
    #(conj % {:evt      evt
              :selector selector
              :cb       cb})))

(defn trigger-click! [evt]
  (let [stack (get-element-stack (.-target evt))]
    (trigger! :click stack)))

; API


; views

(on :click [:statechart]
    (fn [ctx]
      (print "click statechart" ctx)))

(on :click [:statechart :state]
    (fn [ctx]
      (print "click state" ctx)
      false))

(defn state-view [name state]
  (let [{states  :states
         actions :on} state]
    [:div.state

     (when name
       [:div.state-name name])

     (when-not (empty? actions)
       [:div.state-actions
        (for [[name [action]] (seq actions)]
          ^{:key name}
          [:div.action
           [:button.action-name name] "→" [:div.action-target (:target action)]])])

     (when-not (empty? states)
       [:div.state-substates
        (for [[name state] (seq states)]
          ^{:key name} [state-view name state])])]))

(defn machine-view [machine]
  [state-view nil machine])

(defn inspector-view [conn e]
  (let [{todo :inspector/selected-entity} @(p/pull conn [{:inspector/selected-entity [:todo/completion :todo/description]}] e)
        description (:todo/description todo)]

    [:div.inspector
     [:div.attribute.is-inline
      [:div.attribute-name "description"]
      [:div.attribute-value (pr-str description)]]

     [:div.attribute
      [:div.attribute-name "completion"]
      [:div.attribute-value [machine-view completion-machine]]]]))




(defn app [conn]
  [:div {:data-is-root true
         :on-click     trigger-click!}
   [inspector-view conn inspector-id]])

(defn ^:dev/after-load init []
  (dom/render [app conn] (gdom/getElement "root")))




