# Todo Example

Experiment with new architecture where all app state is stored in a Datascript DB and state transitions are modeled with Statecharts.


# First iteration

## Architecture

### Persistence

In Datascript we just store the state. Through the property name we can infer what state machine should interpret it 

```clojure
{:completion-state {:_state :pending }}
```

For that state machines have to be registered for example like this

```clojure
(register-machine :completion-state {...})
```

### Integration with Datascript

Parallel states should be decomposable. Two parallel states are by definition independent of each other. For example we 
could model a todo with two indepedent state machines like this.

```clojure
{ :id 1
  :completion {:_state :pending}
  :view-mode {:_state :read}}
```

The benefit is that we could reuse the view-mode for other things that are not todos but can be edited.


We could implement a helper function that allows us to send events to an entity like this. 
All events have to be prefixed by the name of the property that the event should be sent to.

```clojure
(transact-fsm! conn 1 :completion.toggle)
```

Nested states on the other hand are not independent. Here is an example of a digital lock that can be only
changed if the user is logged in. 

```
App
  LoggedIn
    Logout -> LoggedOut
    
    Lock 
      Opened
        Close -> Closed
      Closed
        Open -> Opened
    
  LoggedOut
    LoginIn -> LoggedIn
```

## Reflection

The decomposition of parallel states is less useful than I hoped. If we take the `:todo/view-mode` as an example
in the enter action of the `:editing` state we set the temp value to `:todo/description`. This prohibits the reuse
of `:todo/view-mode` as a generic state for any entity that can be editable. We might solve this problem by introducing
abstract computed properties that 

There are some subtle problems with invalid state transitions. If we save a todo by pressing enter a save event gets
triggered this causes a rerender of the view which triggers a blur on the input element because it's removed. The blur
triggers another save event but we are already in the `:viewing` state so the event triggers a warning that it's invalid.

Conceptually the paradigm of statecharts is nice to reason about but the representation as a clojure map makes it
sometimes cumbersome because you have to remember exactly the schema of the machine definition. It's easy to make mistakes
like writing `:action` instead of `:actions`. I think a simpler text syntax like sketch.systems would be nice extended
with a side panel that allows to add actions etc

<img src="sketches/text-state-editor.png">

There is some friction with the statechart implementation. I don't like that you have to wrap a function with a 
`fsm/update` function if you want to mutate the context. It's also a bit hacky that I pass in the connection and entity
id as properties on the event object. I think these issues can be smoothed over by creating my own wrapper functions in
the right places.

