(ns inspector.api)

; hack: to expose functions that cannot be compiled in bootstrapped mode
; reference them through the global namespace

(defn input-value [evt]
  ((.. js/inspector -helpers -input-value) evt))

(defn trigger!
  ([id attr evt]
   (trigger! id attr evt nil))
  ([id attr evt data]
  ((.. js/inspector -events -trigger_BANG_) id attr evt data)))

(defn add-selector! [attr evt selector cb]
  ((.. js/inspector -events -add_selector_BANG_) attr evt selector cb))

(defn pull [conn selector id]
  ((.. js/datascript -core -pull) conn selector id))

(defn get-conn []
  (.. js/inspector -db -conn))

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
    (-> (pull @(get-conn) [attr-with-ns] id)
        (get attr-with-ns))))

(defn set-attr! [{id :db/id} attr value]
  (transact! (get-conn) [[:db/add id (with-ns attr) value]]))
