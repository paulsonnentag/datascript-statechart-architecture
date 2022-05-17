(ns inspector.helpers)

(defn input-value [evt]
  (-> evt .-target .-value))

;TODO: turn into macro
(defn source-block [forms]
  (->> forms
       (map #(with-out-str (cljs.pprint/pprint %)))
       (clojure.string/join "\n")))


(defn pairs [coll1 coll2]
  (if (> (count coll1) (count coll2))
    (map vector coll1 (concat coll2 (repeat nil)))
    (map vector (concat coll1 (repeat nil)) coll2)))

(defn dissoc-keys [map keys]
  (apply dissoc map keys))

(defn with-index [coll]
  (map-indexed vector coll))