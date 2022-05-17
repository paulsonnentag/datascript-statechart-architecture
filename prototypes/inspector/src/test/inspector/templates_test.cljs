(ns inspector.templates-test
  (:require [clojure.test :refer [deftest is testing]]
            [statecharts.core :as fsm]
            [datascript.core :as d]
            [inspector.templates :as templates]
            [inspector.db :as db]))

(def base-frame-src "<div class=\"flex items-center gap-1 p-1\">
  <input data-name=\"checkbox\" type=\"checkbox\">
  <div data-name=\"description\">{description}</div>
</div>")

(def done-frame-src "<div class=\"flex items-center gap-1 p-1\">
  <input data-name=\"checkbox\" type=\"checkbox\" checked>
  <div data-name=\"description\">{description}</div>
</div>")

(def editing-frame-src "<div class=\"flex items-center gap-1 p-1\">
  <input data-name=\"checkbox\" type=\"checkbox\">
  <input data-name=\"description-input\" value={temp-description}></div>
</div>")

(def base-fragment (templates/parse-fragment base-frame-src))
(def done-fragment (templates/parse-fragment done-frame-src))
(def editing-fragment (templates/parse-fragment editing-frame-src))

(deftest parse-str-bindings

  (testing "without bindings"
    (is (= ["Hello world!"]
           (templates/parse-str-bindings "Hello world!"))))

  (testing "binding in the middle"
    (is (= ["Hello " [:template/binding :user] "!"]
           (templates/parse-str-bindings "Hello {user}!"))))

  (testing "binding at the beginning"
    (is (= [[:template/binding :greeting] " world!"]
           (templates/parse-str-bindings "{greeting} world!"))))

  (testing "binding at the end"
    (is (= ["Hello " [:template/binding :user]]
           (templates/parse-str-bindings "Hello {user}"))))

  (testing "only binding"
    (is (= [[:template/binding :user]]
           (templates/parse-str-bindings "{user}"))))

  (testing "multiple bindings"
    (is (= [[:template/binding :greeting] " " [:template/binding :user] "!"]
           (templates/parse-str-bindings "{greeting} {user}!"))))

  (testing "binding with spaces"
    (is (= ["Hello " [:template/binding :user] "!"]
           (templates/parse-str-bindings "Hello {   user   }!")))))

(deftest parse-attr-bindings
  (is (= {:a       "bar"
          :b       [[:template/binding :foo]]
          :c       [[:template/binding :baz]]
          :message [[:template/binding :greeting] " " [:template/binding :user] "!"]}
         (templates/parse-attrs-bindings {:a       "bar"
                                          :b       "{foo}"
                                          :c       "{  baz }"
                                          :message "{greeting} {user}!"}))))

(deftest parse-fragment
  (testing "without bindings"
    (is (= [:h1 {} "Hello world!"]
           (templates/parse-fragment "<h1>Hello world!</h1>"))))

  (testing "text binding"
    (is (= [:h1 {}
            "Hello " [:template/binding :user] "!"]
           (templates/parse-fragment "<h1>Hello {user}!</h1>"))))

  (testing "attr binding"
    (is (= [:input {:value     [[:template/binding :name]]
                    :on-change identity}]
           (templates/parse-fragment "<input value={name}>"))))


  (testing "example: todo done frame"
    (is (= [:div
            {:class "flex items-center gap-1 p-1"}
            "\n  "
            [:input
             {:data-name "checkbox" :type "checkbox"}]
            "\n  "
            [:div
             {:data-name "description"}
             [:template/binding :description]]
            "\n"]
           base-fragment))))

(deftest get-changeset
  (testing "identical"
    (is (= {}
           (templates/get-changeset [:div {}] [:div {}]))))

  (testing "replace"
    (is (= {:replace [:div {}]}
           (templates/get-changeset [:input {}] [:div {}]))))


  (testing "delete attribute"
    (is (= {:delete-attrs [:checked]}
           (templates/get-changeset
             [:input {:type "checkbox" :checked true}]
             [:input {:type "checkbox"}]))))

  (testing "update attribute"
    (is (= {:set-attrs {:value "bar"}}
           (templates/get-changeset
             [:input {:value "foo"}]
             [:input {:value "bar"}]))))

  (testing "add attribute"
    (is (= {:set-attrs {:checked true}}
           (templates/get-changeset
             [:input {:type "checkbox"}]
             [:input {:type "checkbox" :checked true}]))))

  (testing "identical children"
    (is (= {}
           (templates/get-changeset
             [:div {}
              [:h1 "hello world!"]]
             [:div {}
              [:h1 "hello world!"]]))))

  (testing "replace child"
    (is (= {:child-changes '({:replace [:h2 {} "hello world!"]})}
           (templates/get-changeset
             [:div {}
              [:h1 {} "hello world!"]]
             [:div {}
              [:h2 {} "hello world!"]]))))

  (testing "delete child"
    (is (= {:child-changes '({:replace nil})}
           (templates/get-changeset
             [:div {}
              [:h1 {} "hello world!"]]
             [:div {}]))))

  (testing "append child"
    (is (= {:child-changes '({} {:replace [:h2 {} "subtitle"]})}
           (templates/get-changeset
             [:div {}
              [:h1 {} "hello world!"]]
             [:div {}
              [:h1 {} "hello world!"]
              [:h2 {} "subtitle"]]))))

  (testing "example: todo done variation"
    (is (= {:child-changes '({} {:set-attrs {:checked []}} {} {} {})}
           (templates/get-changeset
             base-fragment
             done-fragment)))))


(deftest apply-changeset
  (testing "identical"
    (is (= [:h1 {} "Hello world!"]
           (templates/apply-changeset
             [:h1 {} "Hello world!"]
             {}))))

  (testing "replace"
    (is (= [:h2 {} "Bye world!"]
           (templates/apply-changeset
             [:h1 {} "Hello world!"]
             {:replace [:h2 {} "Bye world!"]}))))

  (testing "delete"
    (is (= nil
           (templates/apply-changeset
             [:h1 {} "Hello world!"]
             {:replace nil}))))

  (testing "delete attribute"
    (is
      (is (= [:input {:type "checkbox"}]
             (templates/apply-changeset
               [:input {:type "checkbox" :checked true}]
               {:delete-attrs [:checked]})))))

  (testing "set attribute"
    (is
      (is (= [:input {:type "checkbox" :checked true}]
             (templates/apply-changeset
               [:input {:type "checkbox"}]
               {:set-attrs {:checked true}})))))

  (testing "replace attribute"
    (is
      (is (= [:input {:type "checkbox"}]
             (templates/apply-changeset
               [:input {:type "input"}]
               {:set-attrs {:type "checkbox"}})))))


  (testing "replace child"
    (is (= [:div {}
            [:h1 {} "hello world!"]]
           (templates/apply-changeset
             [:div {}
              [:h2 {} "hello world!"]]
             {:child-changes '({:replace [:h1 {} "hello world!"]})}))))

  (testing "delete child"
    (is (= [:div {} nil]
           (templates/apply-changeset
             [:div {}
              [:h1 {} "hello world!"]]
             {:child-changes '({:replace nil})}))))

  (testing "append child"
    (is (= [:div {}
            [:h1 {} "hello world!"]
            [:h2 {} "subtitle"]]
           (templates/apply-changeset
             [:div {}
              [:h1 {} "hello world!"]]
             {:child-changes '({} {:replace [:h2 {} "subtitle"]})}))))


  (testing "example: todo done variation"
    (is (= done-fragment
           (templates/apply-changeset
             base-fragment
             (templates/get-changeset base-fragment done-fragment))))))

(testing "example: todo editing variation"

  ; diff generation is not perfect there is an additional nil at the end
  ; but it's fine for now because it doesn't change the rendered result
  (is (= [:div
          {:class "flex items-center gap-1 p-1"}
          "\n  "
          [:input {:data-name "checkbox" :type "checkbox"}]
          "\n  "
          [:input
           {:data-name "description-input"
            :value     [[:template/binding :temp-description]]}]
          nil]
         (templates/apply-changeset
           base-fragment
           (templates/get-changeset base-fragment editing-fragment)))))

(deftest resolve-attr-bindings
  (is (= "Hello foo!"
         (templates/resolve-attr-bindings
           ["Hello " [:template/binding :user] "!"]
           {:test/user "foo"}
           "test"))))



(def conn (d/create-conn {}))

(d/transact! conn [{:db/id    1
                    :foo/name "foo"}
                   {:db/id    2
                    :bar/name "bar"}
                   {:inspector/frameset   (templates/create-frameset
                                            {:example     {:foo/name "baz"}
                                             :example-src "{:foo/name \"baz\"}"
                                             :frame-src   "<h1>{name}</h1>"})
                    :inspector/attributes [:foo/name]
                    :inspector/name       "foo"}])

(deftest resolve-bindings
  (binding [db/*pull* (fn [conn selector eid]
                        (d/pull @conn selector eid))]

    (testing "without bindings"
      (is (= [:div {} "Hello world!"]
             (templates/resolve-bindings
               [:div {} "Hello world!"]
               conn
               {}
               "test"))))

    (testing "text binding"
      (is (= [:div {} "Hello " "world" "!"]
             (templates/resolve-bindings
               [:div {} "Hello " [:template/binding :user] "!"]
               conn
               {:test/user "world"}
               "test"))))


    (testing "binding with explicit namespace"
      (is (= [:div {} "Hello" " " "world" "!"]
             (templates/resolve-bindings
               [:div {} [:template/binding :other/greeting] " " [:template/binding :user] "!"]
               conn
               {:test/user      "world"
                :other/greeting "Hello"}
               "test"))))

    (testing "nested text binding"
      (is (= [:div {}
              [:h1 {} "Hello " "world" "!"]]
             (templates/resolve-bindings
               [:div {}
                [:h1 {} "Hello " [:template/binding :user] "!"]]
               conn
               {:test/user "world"}
               "test"))))


    (testing "text binding that refers to list of primitives"
      (is (= [:div {} [:<> "1" "2" "3"]]
             (templates/resolve-bindings
               [:div {}
                [:template/binding :list]]
               conn
               {:test/list [1 2 3]}
               "test"))))

    (testing "text binding that refers to list of entities"
      (is (= [:div {}
              [:<>
               [:h1 {:data-db-id 1, :data-name "foo"} "foo"]
               "{:db/id 2, :bar/name \"bar\"}"]]
             (templates/resolve-bindings
               [:div {}
                [:template/binding :list]]
               conn
               {:test/list [{:db/id 1} {:db/id 2}]}
               "test"))))

    (testing "text binding that refers to number"
      (is (= [:div {} "42"]
             (templates/resolve-bindings
               [:div {}
                [:template/binding :number]]
               conn
               {:test/number 42}
               "test"))))

    (testing "text binding that refers to ")

    (testing "attr binding"
      (is (= [:input {:value "foo"}]
             (templates/resolve-bindings
               [:input {:value [[:template/binding :value]]}]
               conn
               {:test/value "foo"}
               "test"))))

    (testing "attr binding mixed"
      (is (= [:button {:label "Logout foo"}]
             (templates/resolve-bindings
               [:button {:label ["Logout " [:template/binding :user]]}]
               conn
               {:test/user "foo"}
               "test"))))))

(defn done-condition [ctx]
  (fsm/matches (:todo/completion ctx) :done))

(defn editing-condition [ctx]
  (fsm/matches (:todo/view-mode ctx) :editing))

(def unparsed-todo-frameset
  {:frame-src base-frame-src
   :variations
   {:done    {:frame-src done-frame-src
              :condition done-condition}
    :editing {:frame-src editing-frame-src
              :condition editing-condition}}})

(def todo-frameset
  (templates/create-frameset unparsed-todo-frameset))

(deftest create-frameset
  (is (=
        {:frame-src base-frame-src
         :frame     base-fragment
         :variations
         {:done    {:frame-src done-frame-src
                    :frame     done-fragment
                    :condition done-condition
                    :changeset (templates/get-changeset base-fragment done-fragment)}
          :editing {:frame-src editing-frame-src
                    :frame     editing-fragment
                    :condition editing-condition
                    :changeset (templates/get-changeset base-fragment editing-fragment)}}}
        (templates/create-frameset unparsed-todo-frameset))))

(deftest render-frameset
  (testing "example: todo done"
    (is (= [:div
            {:class      "flex items-center gap-1 p-1"
             :data-db-id nil
             :data-name  "todo"}
            "\n  "
            [:input
             {:data-name "checkbox" :type "checkbox" :checked true}]
            "\n  "
            [:div {:data-name "description"} "Do something"]
            "\n"]
           (templates/render-frameset todo-frameset
                                      conn
                                      {:todo/description "Do something"
                                       :todo/view-mode   {:_state :viewing}
                                       :todo/completion  {:_state :done}}
                                      "todo"))))

  (testing "example: todo editing"
    (is (= [:div
            {:class      "flex items-center gap-1 p-1"
             :data-db-id nil
             :data-name  "todo"}
            "\n  "
            [:input {:data-name "checkbox" :type "checkbox"}]
            "\n  "
            [:input
             {:data-name "description-input"
              :on-change identity
              :value     "Do something else"}]
            nil]
           (templates/render-frameset todo-frameset
                                      conn
                                      {:todo/temp-description "Do something else"
                                       :todo/description      "Do something"
                                       :todo/view-mode        {:_state :editing}
                                       :todo/completion       {:_state :pending}}
                                      "todo"))))

  (testing "example: todo editing & done"
    (is (= [:div
            {:class      "flex items-center gap-1 p-1"
             :data-db-id nil
             :data-name  "todo"}
            "\n  "
            [:input
             {:data-name "checkbox" :type "checkbox" :checked true}]
            "\n  "
            [:input
             {:data-name "description-input"
              :on-change identity
              :value     "Do something else"}]
            nil]
           (templates/render-frameset todo-frameset
                                      conn
                                      {:todo/temp-description "Do something else"
                                       :todo/description      "Do something"
                                       :todo/view-mode        {:_state :editing}
                                       :todo/completion       {:_state :done}}
                                      "todo")))))