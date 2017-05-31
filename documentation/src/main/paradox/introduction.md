# Introduction

The aim of this library is to provide a framework for developers to implement applications based on Domain Driven Design and
Reactive principles.

In order to fully understand how to use this library we should get familiar to the key concepts of DDD and Reactive architectures.

DDD should be use to govern the complexity on large/enterprise systems. The important concept is that in a large system having
a global domain model will introduce complexity which are not related to the problem to solve.
DDD uses a _Divide & Conquer_ approach, splitting the system domain in sub domains, organised in modules named `Bounded Context`.
Hence the whole platform is a collection of these self-contained modules with a well defined sub model and which delivers some business functionality. 
Bounded Context might require to interact with other modules through a well defined protocol, however a principle to follow here is to minimize the interactions.
This way each module is cohesive enough within itself but still loosely coupled with other modules.

Each `Bounded Context` is made of atomic components called `micro-service` which manipulates the `domain model` in order to deliver the business functionality.

## Domain Model

Modelling is one of the most critical aspect when defining a solution. 
When designing a **domain model** you need to distinguish between elements which might have an identity from the one which might not require it:

* Entity
  - Has an Identity
  - Passes through different states in the life cycle
  - Usually has a defined life cycle in the business
* Value Object
  - Semantically immutable
  - Can be freely shared across entities

### Life cycle of a domain object

For each object defined in your domain is important that you can handle the following events: 

* Creation - How the object is created within the system
* Participation in behaviors - How you represent the object in memory when interacting with other entities or value objects.
  Complex entities can be made of other entities and/or value objects.
* Persistence - How the object is maintained in a persisted form.

The way we manage those 3 events can be defined trough well known _patterns_.

#### Factories

The object creation should be centralised in order to avoid handle in a more controlled way how the instances are created.

#### Aggregates

Some entities can be more complex than other and be made of a composition of other model objects.
One way you can use to visualise the entire graph of objects is to think of it as forming a _consistency boundary_
within itself. This graph is what is so called aggregate.

An aggregate can consist of one or more entities and value objects (and other primitive attributes). Beside to ensuring
the consistency of business rules, an aggregate within a bounded context, is also often looked at as a transaction boundary
in the model.
One of the entities in the aggregate forms the _aggregate root_. It's sort of the guardian of the entire graph and serves as
the single point of interaction of the aggregate with its clients. The aggregate root has 2 constraints to enforce:

* Ensure the consistency boundaries of business rules and transactions within the aggregate (Consistency - Intrinsic property)
* Prevent the implementation of the aggregate from leaking out its clients, acting as a facade for all the operation supported
  by the aggregate (Interface/Protocol - Extrinsic property)
  
**Learn more on Aggregate Root on this article (Effective Aggregate Design):**

https://vaughnvernon.co/?p=838






## Micro Service

* Service
  - Models a use case of the business
  - Manipulates multiple entities/value objects

# Interactions with Reactive Principles

* CQRS/ES














# Introduction

The aim for this framework is to provide a design pattern for development team, in order to implement applications which
respect Domain Driven Design and Reactive principles.


## Target Architecture

Any domain model of nontrivial complexity is really a collection of smaller models, each with its own data and domain vocabulary.
The term `Bounded Context` denotes one such smaller model within the whole.

As such a `Bounded Context` is a self-contained module but it might still requires to interact with other `Bounded Contexts`.
Is important when designing a `Bounded Context` to make sure it is self-contained, such in a way interactions with other modules
are minimised, in order to be cohesive enough within itself and yet loosely coupled with other `Bounded Contexts`.


## Domain Model

_Entities, Aggregate Root, Value Object_

## Micro Service



## Processor

## View




#### Notes

Some business functionality in a cohesive, loosely-coupled unit that **masters its own data**.
A Bounded Context is made of Micro-Services which share a common model in order to deliver a complex business functionality.
Each Bounded context is characterized by an internal model (intrinsic model) and an external interface (extrinsic model) which
is the exposed API.

If we consider Amazon platform as target architecture then we can break it into possible following Bounded Context:
* Catalog Manager
* Order Management System
* Delivey Management System
* Account Management System
* Payment System
* etc ...

An important rule to respect is the following:
Bounded Context can communicate each other only by using their exposed API (extrinsic model).