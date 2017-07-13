# Component

## Introduction

A component implements some business logic inside a Bounded Context.
Components are responsible for:

- Updating the internal data in a consistent way
- Respond to specific queries over the data

Because of the above responsibilities we tend to specialise them into:

- Processors: components responsible to handle the `write-model` 
- Views: components responsbile to handle the `read-model`

## Processor

A processor is a component that has to react to specific commands and input events,
in order to update the bounded context status in a consistent way and generate notification events
for each side effect performed.

A processor owns a model and defines stateful services to accomplish to the above responsibility

Based on the above definition the processor responsibilities is to implement all the possible function 
that manipulate the status change for a specific entity of your model within the `Bounded Context`.

In a `Bounded Context`, status changes can be triggered by:

- A _valid_ `Command` sent to the `Bounded Context` queue
- A reaction to an `Event` which the `Bounded Context` has subscribed
- A time based trigger within the `Bounded Context`

A possible pseudo-code definition of a processor can be the below:

```scala

/**
* Describes how the current entity status passed as input parameter
* should be changed into the final output Entity value in front of 
* a command, and the Event should be generated to describe the Side 
* Effect.
*/
type Cmd[Entity] = (Entity, _ <: Command) => (Entity, _ <: Event)

/**
* Describes how the current entity status passed as input parameter
* should be changed into the final output Entity value in front of 
* an event observed, and the Event should be generated to describe the Side 
* Effect.
*/
type Evt[Entity] = (Entity, _ <: Event) => (Entity, _ <: Event)

trait Processor[Entity] {
  val cmdModifiers: Set[Cmd[Entity]]
  val evtModifiers: Set[Evt[Entity]]
}

```

Example of a command modifier for an `Option[Wallet]`:

```scala
val debitWalletCmd: Cmd[Option[Wallet]] = command("debitCmd") { (optWallet: Option[Wallet], cmd: DebitCmd) =>
    optWallet.fold(
      throw new RuntimeException(s"Wallet ${cmd.id} does not exist")
    )
    { wallet =>
      if (!wallet.active) throw new RuntimeException(s"Wallet ${wallet.id} is not active")
      val newWallet = wallet.debit(cmd.amount)
      if (newWallet.amount < 0) throw new RuntimeException(s"Insufficient balance for debit amount: ${cmd.amount}")
      (Option(newWallet), Seq(DebitedEvt(cmd.id, cmd.amount)))
    }
}
```


## View

A view is a specific representation of the internal bounded context model suitable to
answer specific queries in an effective manner.
A view react to events in order to update its `read-model` and provide `responses` to
specific `requests`.

A view owns a model and defines stateful services to accomplish to the above responsibility.

## Services

```scala
case class Service[In, Out](id: String)(function: In => Out)
```

### Stateful Service

Is a State Monad on the Out type of the ```Service[In, Out]```

```scala
type StatefulService[S, I, O] = Service[I, State[S, O]]
```

where State is:

```$scala

case class State[S, O] (f: S => (S, O)) {
 ...
}

```
 
