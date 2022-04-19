# Todo Example

Experiment with new architecture where all app state is stored in a Datascript DB and state transitions are modeled with Statecharts.

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

I think the best way for now is to model this as a single statechart.
