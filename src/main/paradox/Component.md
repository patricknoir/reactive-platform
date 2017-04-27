# Component

## Introduction

A component implements some business logic inside a Bounded Context.
Components are responsible for:

- Updating the internal data in a consistent way
- Respond to specific queries over the data

Because of the above responsibility we tend to specialise them into:

- Processors: components responsible to handle the `write-model` 
- Views: components responsbile to handle the `read-model`

## Processor

A processor is a component that has to react to specific commands and input events,
in order to update the bounded context status in a consistent way and generate notification events
for each side effect performed.

A processor owns a model and defines stateful services to accomplish to the above responsibility

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
 
