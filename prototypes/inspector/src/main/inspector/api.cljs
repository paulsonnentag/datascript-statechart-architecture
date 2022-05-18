(ns inspector.api)

(def input-value (.. js/inspector -helpers -input-value))

(def trigger! (.. js/inspector -events -trigger_BANG_))

(def add-selector! (.. js/inspector -events -add_selector_BANG_))

(def clear-selectors! (.. js/inspector -events -clear-selectors_BANG_))

(def pull (.. js/datascript -core -pull))

(def conn (.. js/inspector -db -conn))

(def transact! (.. js/posh -reagent -transact_BANG_))


(declare ^:dynamic *attr-name*)
(declare ^:dynamic *component-name*)

(defn on [evt selector cb]
  (add-selector! *attr-name* evt selector cb))

(defn with-ns [attr]
  (if (namespace attr)
    attr
    (keyword *component-name* (name attr))))

(defn get-attr [{id :db/id} attr]
  (let [attr-with-ns (with-ns attr)]
    (-> (pull @conn [attr-with-ns] id)
        (get attr-with-ns))))

(defn set-attr! [{id :db/id} attr value]
  (transact! conn [[:db/add id (with-ns attr) value]]))

#_(get-attr {:db/id 8} :counter/value)
#_(set-attr! {:db/id 8} :counter/value 3)