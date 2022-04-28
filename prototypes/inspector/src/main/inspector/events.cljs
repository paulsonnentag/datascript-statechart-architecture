(ns inspector.events
  (:require [posh.reagent :as p]
            [datascript.core :as d]
            [statecharts.core :as fsm]
            [inspector.db :as db :refer [conn]]))

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
         machine (get-in @db/schema [property :machine])]
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
