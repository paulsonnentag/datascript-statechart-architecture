(ns inspector.templates
  (:require [clojure.string :refer [trim join]]
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
              binding-name (-> m last trim keyword)
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
              (and (= (count parsed-value) 1)
                   (string? (first parsed-value))) first))  ; unwrap if value is just a string literal without bindings
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
                               (apply-changeset child-fragment child-changeset))
                             children)]
      (into [type changed-attrs] changed-children))

    :else
    fragment))

(defn binding? [x]
  (and (vector? x)
       (= (first x) :template/binding)))

(defn resolve-attr-bindings [value ctx]
  (if (vector? value)
    (->> value
         (map
           #(if (binding? %)
              (let [[_ name] %]
                (get ctx name))
              %))
         (join))
    value))

(defn resolve-bindings [fragment ctx]
  (if (or (string? fragment) (nil? fragment))
    fragment
    (let [[type attrs & children] fragment]
      (if (= type :template/binding)
        (get ctx attrs)
        (let [resolved-children (for [child children]
                                  (resolve-bindings child ctx))

              resolved-attrs (into {}
                                   (for [[name value] attrs]
                                     [name (resolve-attr-bindings value ctx)]))]
          (into [type resolved-attrs] resolved-children))))))

(defn create-frameset
  ([frameset]
   (create-frameset frameset nil))

  ([{:keys [frame-src variations] :as frameset} base]
   (let [frame (parse-fragment frame-src)
         changeset (when base
                     (get-changeset base frame))
         variation-framesets (when variations
                               (into {}
                                     (for [[name variation] variations]
                                       [name (create-frameset variation frame)])))]
     (cond-> (assoc frameset :frame frame)
             changeset (assoc :changeset changeset)
             variation-framesets (assoc :variations variation-framesets)))))

(defn render-frameset
  ([{base-frame :frame variations :variations} ctx]
   (-> (if variations
         (render-frameset base-frame variations ctx)
         base-frame)
       (resolve-bindings ctx)))

  ([base-fragment variations ctx]
   (reduce
     (fn [fragment [_ {:keys [condition changeset] :as whole}]]
       (let [result (if (condition ctx)
                      (apply-changeset fragment changeset)
                      fragment)]
         (print "result" whole result fragment changeset (condition ctx))
         result))
     base-fragment
     variations)))
