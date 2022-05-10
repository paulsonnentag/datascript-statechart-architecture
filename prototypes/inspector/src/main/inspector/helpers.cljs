(ns inspector.helpers)

(defn input-value [evt]
  (-> evt .-target .-value))

;TODO: turn into macro
(defn source-block [forms]
  (->> forms
       (map #(with-out-str (cljs.pprint/pprint %)))
       (clojure.string/join "\n")))