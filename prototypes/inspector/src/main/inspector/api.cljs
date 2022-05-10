(ns inspector.api)

(def input-value (.. js/inspector -helpers -input-value))

(def trigger! (.. js/inspector -events -trigger_BANG_))

(def add-selector! (.. js/inspector -events -add_selector_BANG_))

(def clear-selectors! (.. js/inspector -events -clear-selectors_BANG_))

(def pull (.. js/datascript -core -pull))

(def conn (.. js/inspector -db -conn))

(def transact! (.. js/posh -reagent -transact_BANG_))
