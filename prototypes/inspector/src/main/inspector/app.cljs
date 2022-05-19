(ns inspector.app
  (:require [goog.dom :as gdom]
            [reagent.dom :as dom]
            [datascript.core :as d]
            [posh.reagent :as p]
            [inspector.events :as events]
            [inspector.inspector :as inspector]
            [inspector.db :as db :refer [conn]]
            [inspector.examples.todo :as todo]
            [inspector.examples.todo-list :as todo-list]
            [inspector.examples.counter :as counter]
            [inspector.compiler :as compiler]
            [inspector.templates :as templates]))

(def todo-inspector-id 1)
(def todo-1-id 2)
(def todo-2-id 3)
(def todo-3-id 4)
(def counter-inspector-id 5)
(def todo-list-inspector-id 6)
(def todo-list-id 7)
(def counter-1-id 8)

(p/posh! conn)

(d/transact! conn [{:db/id                         todo-inspector-id
                    :inspector/name                "todo"
                    :inspector/selected-index      0
                    :inspector/schema              {:todo/description :literal
                                                    :todo/completion  :state
                                                    :todo/view-mode   :state}
                    :inspector/frameset            todo/frameset
                    :inspector/expanded-attributes #{:todo/completion :todo/view}}
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
                   {:db/id         counter-1-id
                    :counter/value 5}
                   {:db/id                         counter-inspector-id
                    :inspector/name                "counter"
                    :inspector/selected-index      0
                    :inspector/schema              {:counter/value :literal}
                    :inspector/frameset            counter/frameset
                    :inspector/expanded-attributes #{}}
                   {:db/id                         todo-list-inspector-id
                    :inspector/name                "todo-list"
                    :inspector/selected-index      0
                    :inspector/schema              {:todo-list/todos :literal}
                    :inspector/frameset            todo-list/frameset
                    :inspector/expanded-attributes #{}}
                   {:db/id           todo-list-id
                    :todo-list/todos [2 3 4]}])

(def default-base-frame-src "<div class=\"p-1\">hello!</div>")

(def default-frameset (templates/create-frameset
                        {:example     {}
                         :example-src "{}"
                         :frame-src   default-base-frame-src}))

(defn create-new-component! []
  (p/transact!
    conn
    [{:inspector/name                "new-component"
      :inspector/selected-index      0
      :inspector/schema              {}
      :inspector/frameset            default-frameset
      :inspector/expanded-attributes #{}}]))

(defn ide []
  (let [components (->> @(p/q '[:find ?e ?name
                                :where [?e :inspector/name ?name]] conn)
                        (map (fn [[id name]] {:id id :name name})))]
    [:div.flex.bg-gray-200.w-screen.h-screen
     [:div.p-3.flex.flex-col.gap-2
      {:style {:width "200px"}}

      [:div.text-xl.flex.justify-between
       "components"

       [:button
        {:on-click #(create-new-component!)}
        [:div.icon.icon-plus]]]

      [:div.flex.flex-col.gap-2.p-2

       (for [{:keys [name id]} components]
         ^{:key id}
         [:div name])]]

     [:div.flex.flex-col.gap-4.p-4.w-full.overflow-auto

      (for [{:keys [name id]} components]
        ^{:key id}
        [inspector/view id])]]))

(defn app []
  (if @compiler/!ready?
    [:div.root {:on-click    #(events/dispatch-dom-evt! :click %)
                :on-blur     #(events/dispatch-dom-evt! :blur %)
                :on-key-down #(events/dispatch-dom-evt! :key-down %)
                :on-change   #(events/dispatch-dom-evt! :change %)}
     [ide]]
    [:div.p-3 "loading ..."]))

(defn render []
  (dom/render [app] (gdom/getElement "root")))

(defn ^:dev/after-load init []
  (compiler/init render)
  (render))
