(ns inspector.todo-list
  (:require [posh.reagent :as p]
            [inspector.todo :as todo]
            [inspector.db :refer [conn]]))


;TODO: resolve dynamically how a todo item should be rendered
(def todo-view (:view todo/frameset))


(defn get-db-id [{id :db/id :as e}]
  (or id e))

(def frameset
  {:example    {:todo-list/todos [2 3 4]}
   :variations {}
   :view       (fn [e]
                 (let [in-frameset? (not (number? e))
                       {todos :todo-list/todos} (if in-frameset?
                                                  e
                                                  @(p/pull conn [:todo-list/todos] e))]
                   [:div.p-3 {}
                    [:h1 "Todos"]
                    (for [todo todos]
                      (let [todo-id (get-db-id todo)]
                        ^{:key todo-id}
                          [todo-view todo-id]))]))})