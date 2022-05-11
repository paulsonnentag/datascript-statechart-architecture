(ns inspector.templates
  (:require [clojure.string :refer [trim]]
            [hickory.core :as h]))


(def binding-re (js/RegExp. "\\{([^\\}]*)\\}" "g"))

(defn parse-bindings-str [str]
  (let [last-idx (count str)]
    (loop [tokens []
           prev-idx 0]
      (if-let [m (.exec binding-re str)]
        (let [idx (.-index m)
              binding-expr (first m)
              binding-name (-> m last trim)
              binding {:type :binding :name binding-name}]


          (recur
            (-> tokens
                (cond-> (> idx prev-idx) (conj (subs str prev-idx idx)))
                (conj binding))
            (+ idx (count binding-expr))))

        (do
          (cond-> tokens
                (< prev-idx last-idx) (conj (subs str prev-idx last-idx))))))))

(defn update-template-src [attr src]
  (let [fragment (h/as-hickory (h/parse src))]
    (print "dom:")
    (cljs.pprint/pprint fragment)))