# Reactive Bounded Context

## Introduction

A `Bounded Context` is a self contained system which masters its internal model and implements some business functionality.

A `Bounded Context` can interact with other components only through a well defined protocol of communication
which is effectively its `API`.

In order to be `reactive` the API is based on asynchronous message driven communication, this approach will give us several advantages
 which have been described [here](TODO).
 
To define a `Bounded Context` we need to describe:
 
 - an internal model
 - micro-services to work on the model
 - protocol to expose the functionality and/or interact with other systems
  
  
## Bounded Context Project Structure

Each `Bounded Context` is a root project identified by a `name` and `version`.
i.e.:
> Bounded Context: name = `account-system`, version = `1.0.0`

> **NOTE**: by convention all the bounded context name should have a suffix `system`, this is preferred to others like `manager`.

A `Bounded Context` project has always a sub-module called `protocol` as a separate entity. This is where we define
all the messages in order to interact with this specific `Bounded Context`.


- [bounded-context]
  - protocol
  - service
    - [service-name]
    
### Protocol Project Organization

The protocol project contains the ADTs (Algebric Data Types) which define the protocol. As Bounded Context protocol is based on the CQRS
pattern we will have the following messages:

- Command
- Event
- Request
- Response

The platform extends the above with some administration notifications:

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

All the micro-services of the `Bounded Context` should be a module which leaves inside the folder service and has its own module folder
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
 
 #### Domain
 The **domain** package contains all the domain object used by this service in order to implement some business logic. 
 This is the internal domain which is not publicly exposed outside.
 There are some conventions to follow fo implement an effective model for micro-services based on __Entity__, __ValueObject__, 
 __Aggregate__ and __AggregateRoot__ (find more on how to design effective models [here](TODO)).
 
 #### Processor/View/Service
 This is where you organise your micro-services functionality, segregating the components which manipulate the entity status of your
 module (write-part: `processors`) and the one which aggregate information to be exposed (read-part: `views`).
 You might also have some other services which can be organised under the `service` sub-package.