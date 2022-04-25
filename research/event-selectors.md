# Event selectors

## Example

Using the statechart view as an example because it's sufficiently complex and it let's me test how well this approach
works if even the editor is built around the same architecture.

How can we represent the following state machine?

```clojure
{:id      :completion-machine
 :initial :pending
 :states
 {:pending {:on
            {:toggle :done}}
  :done    {:on
            {:toggle :pending}}}}
```

Splitting it into multiple entities. This would be more pure but the drawback is that we couldn't
use clj-statechart anymore

```clojure
{:db/id 1
 :sc/state {:initial 1
            :name :completion
            :states [2 3] }}

{:db/id 2
 :sc/state {:parent 1
            :name :pending
            {:on {:toggle 3}}}}

{:dib/id 3
 :sc/state {:parent 1
            :name :done
            {:on {:toggle 2}}}}
```

Alternatively we could just store the statemachine as a single property

```clojure
{:db/id
 :sc/machine {...}}
```


```clojure
(defn machine-view [machine]
  (let [{machine :sc/machine
         selected-state :sc/selected-state} @(p/pull conn '[*] machine)
        states (:states machine)]
  
  [:div.Machine
   [:h1 {:id machine}] 
   [:ul 
      (for [[name state] (seq states)]
      [state-view [name state selected-state []]])]]
  ))


(defn state-view [name state selected-state parent-state-path]
  [let [state-path (conj parent-state-path name)]]
  
    [:div.State
     [:ul
      (for [[name state] (seq (:states state))]
        [state-view [name state selected-state []]])]])

```

Here in this event handler we need access to the state-path property that is defined in the
component. Somehow we need a get-prop function to recover

```clojure
(on :click [:Machine :State] 
    (fn [machine state]
      (let [selected-state (get-prop :state-path state)]
        (transact! [machine :scm/selected-state selected-state]))
      
      ; return false to stop propagation
      false))
```

This could be achieved through a macro that generates a unique id for each component and
associate the local variables in a global map with the id

## Spec

Event selectors consists of an event name and a element selection that it should react to

```
(on :click ["machine"] (fn []))
(on :click ["machine" "state"] (fn []))
````

The element element selector is a basic css selector the names are interpreted as class names. 
The element selectors above are equivalent to the following css selectors

```css
.machine { ... }
.machine .state { ... }
```

If an element that is part of the selection has a `data-view-id` or `data-db-id` attribute
it will be accessible 

**Example**

If this event selector is triggered

```clojure
(on :click ['machine'] 
    (fn [{:keys [machine]}]
      (print machine)))
```

by this event

```html
<div class="machine" data-view-id="2" data-db-id="3"/>
```

the print result would be 

```clojure
{:view/id 2 :db/id 3}
```

The `:db/id` property can be used together with `pull`, `q` and `transact!` to get data
of the object that the view represents

The `:view/id` can be used to access computed properties from the view with `get-property`

### Event resolution

When a click is triggered on the A2 state

```
<div class="machine" data-view-id="1" data-db-id="1">
   <h1>Some state</h1>
   <ul>
    <li class="state" data-view-id="2">
        <h1>A</h1>
        <ul>
            <li class="state" data-view-id="3">
                <h1>A1</h1>
            </li>
            <li class="state" data-view-id="4">
               <h1>A2</h1>
            </li>
        </ul>
    </li>
    <li class="state" data-view-id="5">
       <h1>B</h1>
    </li>
   </ul>
</div>
```

The following element path is generated

```
[{:class "state" :view/id 4}
 {:class "state" :view/id 2}
 {:class "machine" :view/id 1}]
```

Then we match the element path against the selector

We start with the last element and match it against the last element 



Events can be canceled by returning false
