(ns inspector.templates
  (:require [clojure.string :refer [trim]]
            [clojure.data :refer [diff]]
            [hickory.core :as h]
            [inspector.helpers :refer [pairs dissoc-keys]]))

(def binding-re (js/RegExp. "\\{([^\\}]*)\\}" "g"))

(defn parse-str-bindings [str]
  (let [last-idx (count str)]
    (loop [tokens []
           prev-idx 0]
      (if-let [m (.exec binding-re str)]
        (let [idx (.-index m)
              binding-expr (first m)
              binding-name (-> m last trim symbol)
              binding [:template/binding binding-name]]

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


(defn -parse-bindings* [fragment]
  (if (string? fragment)
    (parse-str-bindings fragment)
    (let [[type attrs & children] fragment
          parsed-attrs (parse-attrs-bindings attrs)
          parsed-content (mapcat -parse-bindings* children)]
      [(into [type parsed-attrs] parsed-content)])))

(defn parse-bindings [fragment]
  (first (-parse-bindings* fragment)))

(defn unwrap-body [document]
  (-> document first last (nth 2)))

(defn parse-fragment [src]
  (-> src h/parse h/as-hiccup unwrap-body parse-bindings))

(defn update-template-src [attr src]
  (let [fragment (parse-fragment src)]))

(defn get-changeset [base variation]
  (if (string? base)
    (if (= base variation) {} {:replace variation})
    (let [[base-type base-attrs & base-children] base
          [variation-type variation-attrs & variation-children] variation]
      (if-not (= base-type variation-type)
        {:replace variation}
        (let [[base-only-attrs set-attrs _] (diff base-attrs variation-attrs)

              delete-attrs (->> base-only-attrs
                                keys
                                (remove #(contains? set-attrs %)))

              content-changesets (for [[base-child variation-child] (pairs base-children variation-children)]
                                   (get-changeset base-child variation-child))

              content-changesets? (and content-changesets
                                       (some not-empty content-changesets))]
          (cond-> {}
                  (not-empty delete-attrs) (assoc :delete-attrs delete-attrs)
                  (not-empty set-attrs) (assoc :set-attrs set-attrs)
                  content-changesets? (assoc :child-changes content-changesets)))))))

(defn apply-changeset [fragment changeset]
  (cond
    (contains? changeset :replace)
    (:replace changeset)

    (not-empty changeset)
    (let [[type attrs & children] fragment
          {:keys [delete-attrs set-attrs child-changes]} changeset
          changed-attrs (cond-> attrs
                                delete-attrs (dissoc-keys delete-attrs)
                                set-attrs (merge set-attrs))
          changed-children (if child-changes
                             (for [[child-fragment child-changeset] (pairs children child-changes)]
                               (do
                                 (print child-fragment child-changeset (apply-changeset child-fragment child-changeset))
                                 (apply-changeset child-fragment child-changeset)))
                             children)]
      (into [type changed-attrs] changed-children))

    :else
    fragment))

