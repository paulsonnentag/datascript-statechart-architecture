(ns inspector.templates
  (:require [clojure.string :refer [trim]]
            [clojure.data :refer [diff]]
            [hickory.core :as h]
            [inspector.helpers :refer [pairs with-idx]]))

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
              (= (count parsed-value) 1) first))            ; unwrap if value is just a list with one element
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
      (cond-> fragment
              content (assoc :content (->> content (map parse-bindings) flatten))
              attrs (update :attrs parse-attrs-bindings)))))

(defn unwrap-body [document]
  (-> document :content first :content second :content first))

(defn parse-fragment [src]
  (-> src h/parse h/as-hickory unwrap-body parse-bindings))

(defn normalize-fragment [{:keys [content] :as fragment}]
  (if content
    (let [normalized-content (remove
             #(and (string? %)
                   (= (trim %) ""))
             content)]
    (assoc fragment :content normalized-content))
    fragment))

(defn update-template-src [attr src]
  (let [fragment (parse-fragment src)]))


(defn get-changeset [{base-tag   :tag
                      base-attrs :attrs
                      base-content :content
                      :as base}
                     {variation-tag   :tag
                      variation-attrs :attrs
                      variation-content :content
                      :as             variation}]
  (if-not (= base-tag variation-tag)
    {:replace variation}
    (let [[base-only-attrs set-attrs _] (diff base-attrs variation-attrs)
          delete-attrs (->> base-only-attrs
                            keys
                            (remove #(contains? set-attrs %)))

          content-changesets (when (and base-content variation-content)
                               (reduce
                                 (fn [content-changeset [idx [base-child variation-child]]]
                                   (let [child-changeset (get-changeset base-child variation-child)]
                                   (if (not-empty child-changeset)
                                     (assoc content-changeset idx child-changeset)
                                     content-changeset)))
                                 {}
                                 (with-idx (pairs base-content variation-content))))

          content-changesets? (and content-changesets
                                   (some not-empty content-changesets))]
      (cond-> {}
              (not-empty delete-attrs) (assoc :delete-attrs delete-attrs)
              (not-empty set-attrs) (assoc :set-attrs set-attrs)
              content-changesets? (assoc :content content-changesets)))))