# Introduction

#Notes


##Domain Model
More formally, a domain model is a blueprint of the relationships between the various
entities of the problem domain and sketches out other important details, such as
the following:

* Objects of the domain (FP - ADT Algebric Data Types)
* Behaviour (Micro Services)
* Language
* Context which the model operates (Bounded Context)

*Challenge*: Manage complexity

Model complexity = Inherited from the system (incidental) + Business problem complexity (essential, cannot be avoided)


DDD allows to handle model complexity dealing and minimizing the incidental element of the equation.

##Bounded Context
Any model of nontrivial complexity is really a collection of smaller models, each one with its own data and domain vocabulary.
A "Bounded Context" is representing one of such smaller models within the whole.

Design principle: communications between bounded contexts should be minimised as much as possible 