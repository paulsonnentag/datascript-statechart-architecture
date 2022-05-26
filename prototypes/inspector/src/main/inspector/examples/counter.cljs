(ns inspector.examples.counter
  (:require [inspector.templates :as templates]
            [inspector.events :as events]
            [inspector.helpers :refer [source-block]]
            [inspector.api]))


; VIEW-MODE

(def view-evt-selector-src
  (source-block
    '[(on :click [:plus]
          (fn [{:keys [counter]}]
            (let [value (get-attr counter :value)]
              (set-attr! counter :value (+ value 1)))))

      (on :click [:minus]
          (fn [{:keys [counter]}]
            (let [value (get-attr counter :value)]
              (set-attr! counter :value (- value 1)))))]))

(events/update-event-selectors-src :counter/view view-evt-selector-src)

(def base-frame-src "<div class=\"p-3 flex flex-col gap-2\">
  <h1 class=\"text-xl text-center\">Counter</h1>

  <div class=\"flex gap-2 items-center\">
    <button class=\"rounded bg-gray-200 p-3\" data-name=\"plus\">+</button>
    {value}
    <button class=\"rounded bg-gray-200 p-3\" data-name=\"minus\">-</button>
  </div>
</div>")

(def frameset
  (templates/create-frameset
    {:example   {:counter/value [2 3 4]}
     :frame-src base-frame-src}))
