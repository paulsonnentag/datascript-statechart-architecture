(ns inspector.examples.todo-list
  (:require [inspector.templates :as templates]))

(def base-frame-src "<div class=\"p-3\">
  <h1>Todos</h1>
  {todos}
</div>")

(def frameset
  (templates/create-frameset
    {:example   {:todo-list/todos [2 3 4]}
     :frame-src base-frame-src}))
