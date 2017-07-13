# Complex Transactions

@@toc{ depth=3 }

### Saga

Sometime there are operations that need to be performed across different `Bounded Contexts` and
the atomicity of the operation should be guaranteed within a specific execution context.

A good example of this scenario can be the following:

We have 3 ```Bounded Context```:

* Account System
* Sportsbook Catalog System
* Bet Engine System

Let's assume a customer wants to place bet on a specific event, a sequence of well defined
operations must occur:

1. Check if the customer is enabled 
2. Money are deducted successfuly from his wallet
3. The sports event the customer wants to place a bet is active and the price hasn't changed 
4. The bet has been successfully stored

In order to generate an event for the PlaceBet command the aboe 4 points must be executed successfully.

As the above operation is spanning across different bounded context we need to introduce a new concept:
__Saga__.

The __Saga__ incapsulate some how the execution context for this command and provide compensation operation
in case one or more of the above actions fails.

For this purpose the Message Fabric will implement a CPE based on streaming execution.

The __Saga can be described as a stream where after step 1, 2, 3 have been executed (potentially in parallel)
the step 4 can finally be executed and terminated.

The saga is also responsible to recover from a partial failure of each of this operation, making sure that the system
is brought back into a consistent status and also preserve the order.

The __Saga__ is a complex processor that operates across different bounded contexts but it is at all means a Stateful Service
as the processor, hence it uses the same implementation strategy to recover from Fail-Over (Each Saga instance is a Persistent Actor instance).

```scala

case class SagaCommand(commands: Seq[Command], compensations: Seq[Compensation]) extends Command

(Command => SagaCommand) => Seq[Event] => Event 
```


### Stream

Sometime a Command to be executed need to be enriched with information that are store in a different `Bounded Context`.
For this purpose in our anti-corruption layer which is the Message Fabric we can define a Stream.
The Stream will exentially take the command, query the bounded context necessary to gather all the information in order for
the target `Bounded Context` to be able to execute the command.

```scala

(Command => Set[Request]) => Set[Response] => Command

```