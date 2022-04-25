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


(def schema {:statechart/machine {}})

(defonce conn (d/create-conn schema))

(p/posh! conn)

(d/transact! conn [{:db/id              1
                    :statechart/machine completion-machine}])

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

(on :click [:statechart]
    (fn [ctx]
      (print "click statechart" ctx)))

(on :click [:statechart :state]
    (fn [ctx]
      (print "click state" ctx)
      false))

; views

(defn state-view []
  (let [ui-id (get-ui-id)]
    (r/create-class
      {:display-name "state-view"
       :reagent-render
       (fn [conn name state]
         [:div.state {:data-view-id ui-id :data-name "state"}
          [:h1.state-name name]])})))

(defn statechart-view []
  (let [ui-id (get-ui-id)]
    (r/create-class
      {:display-name "statechart-view"
       :reagent-render
       (fn [conn e]
         (let [{machine :statechart/machine} @(p/pull conn '[*] e)
               states (:states machine)
               name (:id machine)]
           [:div.state {:data-view-id ui-id :data-db-id e :data-name "statechart"}
            [:h1.state-name name]
            [:div.state-states
             (for [[name state] (seq states)]
               ^{:key name} [state-view conn name state])]]))})))


(defn app [conn]
  [:div {:data-is-root true
         :on-click     trigger-click!}
   [statechart-view conn 1]])

(defn ^:dev/after-load init []
  (dom/render [app conn] (gdom/getElement "root")))




