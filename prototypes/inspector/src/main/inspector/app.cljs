(ns inspector.app
  (:require [goog.dom :as gdom]
            [reagent.dom :as dom]
            [datascript.core :as d]
            [posh.reagent :as p]
            [inspector.events :as events]
            [inspector.inspector :as inspector]
            [inspector.db :refer [conn]]
            [inspector.todo :as todo]
            [inspector.todo-list :as todo-list]))

(def todo-inspector-id 1)
(def todo-1-id 2)
(def todo-2-id 3)
(def todo-3-id 4)
(def todo-list-inspector-id 5)
(def todo-list-id 6)

(p/posh! conn)

(d/transact! conn [{:db/id                         todo-inspector-id
                    :inspector/name                "todo"
                    :inspector/selected-index      0
                    :inspector/attributes          [:todo/description :todo/completion :todo/view-mode]
                    :inspector/frameset            todo/frameset
                    :inspector/expanded-attributes #{:todo/completion :view}}
                   {:db/id            todo-1-id
                    :todo/description "Do something"
                    :todo/completion  {:_state :pending}
                    :todo/view-mode   {:_state :viewing}}
                   {:db/id            todo-2-id
                    :todo/description "Do something else"
                    :todo/completion  {:_state :done}
                    :todo/view-mode   {:_state :viewing}}
                   {:db/id            todo-3-id
                    :todo/description "Do another thing"
                    :todo/completion  {:_state :pending}
                    :todo/view-mode   {:_state :viewing}}
                   {:db/id                         todo-list-inspector-id
                    :inspector/name                "todo-list"
                    :inspector/selected-index      0
                    :inspector/attributes          [:todo-list/todos]
                    :inspector/frameset            todo-list/frameset
                    :inspector/expanded-attributes #{}}
                   {:db/id           todo-list-id
                    :todo-list/todos [2 3 4]}])

(defn app []
  [:div {:data-is-root true
         :on-click     #(events/trigger-dom-evt! :click %)
         :on-blur      #(events/trigger-dom-evt! :blur %)
         :on-key-down  #(events/trigger-dom-evt! :key-down %)
         :on-change    #(events/trigger-dom-evt! :change %)}
   [inspector/view todo-list-inspector-id]
   [inspector/view todo-inspector-id]])

(defn ^:dev/after-load init []
  (dom/render [app] (gdom/getElement "root")))
