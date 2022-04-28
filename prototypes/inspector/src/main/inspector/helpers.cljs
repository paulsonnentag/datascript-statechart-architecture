(ns inspector.helpers)

(defn input-value [evt]
  (-> evt .-target .-value))