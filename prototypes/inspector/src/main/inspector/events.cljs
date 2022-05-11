(ns inspector.events
  (:require [posh.reagent :as p]
            [datascript.core :as d]
            [statecharts.core :as fsm]
            [inspector.db :as db :refer [conn]]
            [inspector.compiler :as compiler]
            [inspector.helpers :refer [source-block]]))

(def empty-selector-group (sorted-set-by #(-> % :selector count -)))

(def selector-groups! (atom {}))

(defn add-selector! [attr evt selector cb]
  (swap!
    selector-groups!
    (fn [selector-groups]
      (let [group (get selector-groups attr empty-selector-group)
            new-group (conj group {:evt evt :selector selector :cb cb})]
        (assoc selector-groups attr new-group)))))

(defn clear-selectors! [attr]
  (swap! selector-groups! #(dissoc % attr)))

(defn dispatch!
  ([evt attr frame base-ctx]
   (loop [handlers (-> @selector-groups! (get attr))]
     (when-let [handler (first handlers)]
       (let [{:keys [cb selector] handler-evt :evt} handler
             continue-flag (when (= handler-evt evt)
                             (loop [ctx base-ctx
                                    frame frame
                                    selector selector]
                               (let [name (last selector)]
                                 (if name
                                   (let [node (last frame)]
                                     (when node
                                       (if (= (:name node) name)
                                         (recur (assoc ctx name node)
                                                (drop-last frame)
                                                (drop-last selector))
                                         (recur ctx
                                                (drop-last frame)
                                                selector))))
                                   (cb ctx)))))]
         (when (not (false? continue-flag))
           (recur (rest handlers))))))))

(defn with-evt-selector-ctx [attr src]
  (str
    (source-block '[
                    (ns inspector.user
                      (:require [inspector.api :refer [add-selector!
                                                       clear-selectors!
                                                       transact!
                                                       trigger!
                                                       pull
                                                       input-value
                                                       *attr-name*
                                                       conn
                                                       on]]))])

    "(binding [*attr-name* " attr "]"

    src

    ")"))


(defn update-event-selectors-src [attr src]
  (let [wrapped-src (with-evt-selector-ctx attr src)]
    (clear-selectors! attr)
    (compiler/eval-src wrapped-src (fn [{error :error}]
                                     (swap! db/schema
                                            #(assoc-in % [attr :evt-selectors-src] {:value src
                                                                                    :error error}))))))
(defn get-frame
  ([element]
   (get-frame element '()))

  ([element frame]
   (when element
     (let [entry (reduce
                   (fn [entry [str-key value]]
                     (let [key (keyword str-key)]
                       (case key
                         :dbId (if-let [id (js/parseInt value 10)]
                                 (assoc entry :db/id id)
                                 entry)
                         :name (assoc entry :name (keyword value))
                         (assoc entry key value))))
                   {}
                   (js/Object.entries (.-dataset element)))

           new-frame (if (empty? entry)
                       frame
                       (conj frame entry))]

       (if (:db/id entry)
         new-frame
         (get-frame (.-parentElement element) new-frame))))))

(defn dispatch-dom-evt! [evt data]
  (when-let [[component & frame] (get-frame (.-target data))]
    (let [component-name (:name component)
          attr (keyword component-name "view")
          ctx (-> {:evt data}
                  (assoc component-name component))]
      (dispatch! evt attr frame ctx))))

(defn trigger!
  ([e attr type]
   (trigger! e attr type {}))

  ([e attr type data]
   (let [entity (d/pull @conn [attr] e)
         machine (get-in @db/schema [attr :machine])]
     (assert machine "no statemachine defined")
     (if-let [state (get entity attr)]
       (let [event (assoc data :e e :type type)
             new-state (fsm/transition machine state event)]
         (p/transact! conn [[:db/add e attr new-state]]))
       (print "ignore event" entity e attr type)))))

