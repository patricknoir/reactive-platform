# Reactive Platform

Leverage:
- Domain Driven Design guidelines to handle complexity
- Reactive Principles to manage side effects
- Functional Programming to simplify highly concurrent systems by reasoning and composing pure functions

## DDD
- Divide and conquer
- Domain specific to solve a specific problem, contextual to a specific system of the enterprise
- Is a scalable model which embrace the Conway law in an Agile SDLC

## Reactive Principles
**All about side effects**
- Responsive
- Resilient
- Fault Tolerant
- Message Driven

## Functional Programming
- Simplify code complexity
- Reason on the problem and composition of pure functions
- Deal on business problem rather than on concurrency


```scala

package org.patricknoir

import scala.concurrent.Future

package object platform {

  case class Service[-In, +Out](id: String, f: PartialFunction[In, Future[Out]])

}

```

A servuce us uniquely identified by an ID which represents the service name.
Service is less powerful than a function, not all elements of the domain are defined
into the codomain, for this reason is a _PartialFunction_.
Our definition of function is in reality an async function, where the Out result is provided
in an asynchronous manner handling the latency side effect.

The service sometime has to handle some state.

In => Out can be translated into:

```scala
type StatefulService[S, In, Out] = (S, In) => (S, Out)
```

Given an initial status and an action, it produces a new Status and Output.
Applying curring to this function wont change the semantic:

```scala
type StatefulService[S, In, Out] = In => S => (S, Out)

case class State[S, Out](f: S => (S, Out)) // is the State[S, Out]
```

hence:

```scala
type StatefulService[S, In, Out] = In => State[S, Out]
```

Because it is async is wrapped in a future:

```scala
type AsyncStatefulService[S, In, Out] = In => Future[State[S, Out]]
```

this means the new Out is: 

```scala
type Out2 = Future[State[S, Out]]
```

so the stateful service is nothing more than an alias of Service with the new Out:

```scala
type AsyncStatefulService[S, In, Out] = Service[In, Future[State[S, Out]]]
```





# Scala Features

## Algebric Data Types (ADT)
Scala has built in the language Algebric Data Type under the construct of *case classes*.
ADT are useful to build domain objects, entities and value objects.
i.e. implementing the concepts of *Account*, *Wallet* etc...

## Pure Functions
Help to model domain behaviour.
i.e. *debit*, *credit* etc...

## Functions composition and higher order functions
Help to build complexity by composing basic behaviour.
i.e.: transfer(...) = debit(...) * credit(...) where * = function composition

