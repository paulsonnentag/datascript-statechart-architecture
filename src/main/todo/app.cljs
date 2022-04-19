(ns todo.app
  (:require [statecharts.core :as fsm]
            [datascript.core :as d]
            [goog.dom :as gdom]
            [reagent.dom :as dom]
            [posh.reagent :as p]))



(def schema {:todo/completion  {}
             :todo/view-mode   {}
             :todo/description {}})


(defonce conn (d/create-conn schema))

(p/posh! conn)

(d/transact! conn [{:db/id            1
                    :todo/completion  :done
                    :todo/view-mode   :view
                    :todo/description "do something"}
                   {:db/id            2
                    :todo/completion  :pending
                    :todo/view-mode   :view
                    :todo/description "do something else"}])

(defn todo-view [conn e]
  (print conn e)
  (let [{completion  :todo/completion
         view-mode   :todo/view-mode
         description :todo/description} @(p/pull conn '[*] e)]
    [:div
     [:input {:type     "checkbox"
              :readOnly true
              :checked  (= completion :done)}]

     (if (= view-mode :edit)
       [:input {:value description}]
       [:span description])]))


(defn app [conn]
  (let [todos @(p/q '[:find [?id ...]
                      :where [?id :todo/description _]] conn)]

    [:div
     (for [e todos]
       ^{:key e} [todo-view conn e])]))


(defn init []
  (dom/render [app conn] (gdom/getElement "root")))




