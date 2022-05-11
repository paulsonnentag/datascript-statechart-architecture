(ns inspector.templates
  (:require [clojure.string :refer [trim]]
            [hickory.core :as h]))

(def binding-re (js/RegExp. "\\{([^\\}]*)\\}" "g"))

(defn parse-str-bindings [str]
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

        (cond-> tokens
                (< prev-idx last-idx) (conj (subs str prev-idx last-idx)))))))

(defn parse-attr-bindings [value]
  (if (string? value)
    (let [parsed-value (parse-str-bindings value)]
      (cond-> parsed-value
              (= (count parsed-value) 1) first)) ; unwrap if value is just a list with one element
    value))

(defn parse-attrs-bindings [attrs]
  (into
    (empty attrs)
    (for [[attr value] attrs]
      [attr (parse-attr-bindings value)])))

(defn parse-bindings [fragment]
  (if (string? fragment)
    (parse-str-bindings fragment)
    (let [{:keys [content attrs]} fragment]
      (print "content" content)
      (cond-> fragment
              content (assoc :content (->> content (map parse-bindings) flatten))
              attrs (update :attrs parse-attrs-bindings)))))

(defn unwrap-body [document]
  (-> document :content first :content second :content first))

(defn parse-fragment [src]
  (-> src h/parse h/as-hickory unwrap-body parse-bindings))

(defn update-template-src [attr src]
  (let [fragment (parse-fragment src)]
    (print "dom:")
    (cljs.pprint/pprint fragment)))