# Reactive Bounded Context

## Introduction

A `Bounded Context` is a self contained system which masters its internal model and implements some business functionality.

A `Bounded Context` can interact with other components only through a well defined protocol of communication
which is effectively its `API`.

In order to be `reactive` the API is based on asynchronous message driven communication. This approach will give us several advantages
 which have been described [here](TODO).
 
To define a `Bounded Context` 3 elements are required:
 
 - an internal model
 - micro-services to work on the model (_Processors_ and _Views_)
 - protocol to expose the functionality and/or interact with other systems

  
## Bounded Context Project Structure

Each `Bounded Context` is a root project identified by a `name` and `version`.
i.e.:
> Bounded Context: name = `account-system`, version = `1.0.0`

> **NOTE**: by convention all the bounded context name should have a suffix `system`, this is preferred to others like `manager`.

A `Bounded Context` project has always a sub-module called `protocol` as a separate entity. This is where we define
all the messages in order to interact with this specific `Bounded Context` (Public API).


- [bounded-context]
  - protocol
  - service
    - [service-name]
    
### Protocol Project Organization

The protocol project contains the ADTs (Algebric Data Types) which define the protocol. As the Bounded Context protocol is based on the CQRS
pattern we will have the following messages:

- Command
- Event
- Request
- Response

The platform extends the above with some administration *notifications*:

- Failure
- Auditing
- Logging

The typical package organization for the protocol project should implement the following structure:

```
<organization>.<bounded-context>.protocol.command
<organization>.<bounded-context>.protocol.event
<organization>.<bounded-context>.protocol.request
<organization>.<bounded-context>.protocol.response

<organization>.<bounded-context>.protocol.failure
<organization>.<bounded-context>.protocol.auditing
<organization>.<bounded-context>.protocol.logging
```

All the concrete implementations for commands/events/requests/responses/failures/auditing/logging should be 
living under the associated package.

Is also good practice to suffix the entities depending on their types based on the below table:

| Message Type        | Suffix |
| ------------------- |:------:|
| Command             | Cmd    |
| Event               | Evt    |
| Request             | Req    |
| Response            | Res    |
| Failure             | Fail   |
| Auditing            | Aud    |
| Logging             | Log    |

> **Note:** When there is a corrispondence 1-to-1 between `Command` -> `Event`, is good practice
> to name the event with the past form of the `Command` and suffix `Evt`. i.e.:
> `WalletCreateCmd` -> `WalletCreatedEvt`

### Service Module

Each micro-service in the `Bounded Context` should be a module which leaves inside the folder service and has its own module folder
which reflects the project name.

> **Note:** is recommended to suffix the service name with `service`.

For example the service `session-service` which is part of the bounded context `account-system` will leave:

```
account-system/
  . . .
  service/
    session-service/
    
```

The typical package organization for the service module should implement the following structure:

```
<organization>.<bounded-context>.<service-name>.domain
<organization>.<bounded-context>.<service-name>.processor
<organization>.<bounded-context>.<service-name>.view
<organization>.<bounded-context>.<service-name>.service
```

### Domain
 The __domain__ package contains all the domain objects used by this service in order to implement some business logic. 
 This is the internal domain which is not publicly exposed and only used internally to the `Bounded Context`.
 There are some conventions to follow to implement an effective model for micro-services based on __Entity__, __ValueObject__, 
 __Aggregate__ and __AggregateRoot__ (find more on how to design effective models [here](TODO)).
 
 
### Processor/View/Service
  This is where you organise your micro-services functionality, segregating the components which manipulate the entity status of your
  module (write-part: `processors`) and the one which aggregate information to be exposed (read-part: `views`).
  
### Processor
  
  A processor is a service responsible to manipulate state changes of a specific __AggregateRoot__ with in the `Bounded Context`.
  
  __Processor Definition__ :
  
```scala
 
  /** You describe a Processor by defining its component name and version, a descriptor which
    * contains some information related to the semantic on how processors are created (singleton, entity etc...)
    *
    * A processor is in charge to manipulate the root-aggregate `W` in order to guarantee consistence, this is done
    * by defining the `reducers` commandModifiers, eventModifiers, which describes how the root-aggregate should be
    * modified in reaction to specific commands or events (within the ProcessorProps[W].
    *
    * @param id processor component identifier.
    * @param version processor component version.
    * @param descriptor describes how this view should be create (singleton, per entity id etc...).
    * @param props describes the behaviour of this processor.
    * @tparam W represents the root-aggregate type.
    */
  sealed case class Processor[W](
    override val id: String,
    override val version: Version,
    override val descriptor: ProcessorDescriptor,
    override val model: W,
    override val props: ProcessorProps[W]
  ) extends Component[W]
  
  case class ProcessorProps[W](
    commandModifiers: Set[CmdInfo[W]] = Set.empty[CmdInfo[W]],
    eventModifiers: Set[Evt[W]] = Set.empty[Evt[W]],
    queries: Set[AskInfo[W]] = Set.empty[AskInfo[W]]
  ) extends ComponentProps[W]
  
```
  
  __Example__ :
  
```scala
  val walletProcessorDef = ProcessorDef[Option[Wallet]](
      id = "walletProcessor",
      version = Version(1, 0, 0),
      descriptor = shardingDescriptor,
      model = None,
      propsFactory = _ => ProcessorProps[Option[Wallet]](
        commandModifiers = Set(
          createWalletCmd,
          debitWalletCmd,
          createWalletCmd
        ),
        queries = Set(getBalanceReq)
      )
      
```
  
  __Create Wallet Command__ :
  
  A CmdInfo[M] is a function from:
```scala
  type CmdInfo[M] = String => (M, _ <: Command) => (M, _ <: Event)
```
  
```scala
  val createWalletCmd: CmdInfo[Option[Wallet]] = command("walletCreateCmd") { (optWallet: Option[Wallet], cmd: WalletCreateCmd) =>
      (Option(Wallet(cmd.id, cmd.amount, cmd.active)), Seq(WalletCreatedEvt(cmd.id, cmd.amount, cmd.active)))
    }
 
```