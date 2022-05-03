(ns inspector.db
  (:require [datascript.core :as d]))

(def schema (atom {:inspector/name                {}
                   :inspector/selected-index      {}
                   :inspector/attributes          {}
                   :inspector/view                {}
                   :inspector/expanded-attributes {}
                   :todo/description              {}
                   :todo/completion               {}
                   :todo/view-mode                {}
                   :todo-list/todos               {:db/type :db.type/ref :db/cardinality :db.cardinality/many}}))

(defn register-machine! [key machine]
  (swap! schema #(assoc-in % [key :machine] machine)))

(def conn (d/create-conn @schema))
