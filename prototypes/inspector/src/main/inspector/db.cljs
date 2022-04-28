(ns inspector.db
  (:require [datascript.core :as d]))

(def schema (atom {:inspector/name       {}
                   :inspector/entity     {:db/type :db.type/ref}
                   :inspector/attributes {}
                   :inspector/view       {}
                   :todo/description     {}
                   :todo/completion      {}
                   :todo/view-mode       {}}))

(defn register-machine! [key machine]
  (swap! schema #(assoc-in % [key :machine] machine)))

(def conn (d/create-conn @schema))