(ns inspector.app
  (:require [goog.dom :as gdom]
            [reagent.dom :as dom]
            [datascript.core :as d]
            [posh.reagent :as p]
            [inspector.events :as events]
            [inspector.inspector :as inspector]
            [inspector.db :as db :refer [conn]]
            [inspector.todo :as todo]
            [inspector.todo-list :as todo-list]
            [inspector.compiler :as compiler]
            [inspector.templates :as templates]))

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
                    :inspector/schema              {:todo/description :literal
                                                    :todo/completion  :state
                                                    :todo/view-mode   :state}
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
                    :inspector/schema              {:todo-list/todos :literal}
                    :inspector/frameset            todo-list/frameset
                    :inspector/expanded-attributes #{}}
                   {:db/id           todo-list-id
                    :todo-list/todos [2 3 4]}])

(def default-base-frame-src "<h1 class=\"p-1 text-lg\">hello!</h1>")

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
    [:div.ide {}
     [:div.ide-sidebar.p-2.flex.flex-col.gap-2
      (for [{:keys [name id]} components]
        ^{:key id}
        [:div name])

      [:button.bg-gray-200.p-1
       {:on-click #(create-new-component!)}
       "new component"]]
     [:div.flex.flex-col.gap-4.p-2.w-full

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
