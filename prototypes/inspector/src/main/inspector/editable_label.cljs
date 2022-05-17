(ns inspector.editable-label
  (:require [reagent.core :as r]
            [inspector.helpers :refer [input-value]]))

(defn view []
  (let [!editing? (r/atom false)]
    (fn [value on-change]
        (if @!editing?
          [:input {:value     value
                   :ref      (fn [el]
                                (when el
                                  (.focus el)))
                   :on-blur   #(reset! !editing? false)
                   :on-change #(on-change (input-value %))}]
          [:div {:on-click #(reset! !editing? true)}
           (str value)]))))