(ns inspector.db
  (:require [datascript.core :as d]
            [reagent.core :as r]
            [posh.reagent :as p]))

(defonce schema (r/atom {:inspector/name                {}
                         :inspector/selected-index      {}
                         :inspector/attributes          {}
                         :inspector/view                {}
                         :inspector/expanded-attributes {}
                         :todo/description              {}
                         :todo/completion               {}
                         :todo/view-mode                {}
                         :todo-list/todos               {:db/type :db.type/ref :db/cardinality :db.cardinality/many}}))

(defn add-evt-selector-src! [key src]
  (swap! schema #(assoc-in % [key :evt-selectors-src] src)))

(defn register-machine! [key machine]
  (swap! schema #(assoc-in % [key :machine] machine)))

(def conn (d/create-conn @schema))

(def ^:dynamic *pull*
  (fn [conn selector eid]
    @(p/pull conn selector eid)))

(defn pull [conn selector eid]
  (*pull* conn selector eid))
