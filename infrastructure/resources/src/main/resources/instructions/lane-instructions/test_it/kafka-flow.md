# IT Kafka / Outbox / Inbox Flow

## Flow

Use `forgeit.kafka()` and existing local helpers only when the assigned flow involves:

- Kafka;
- outbox;
- inbox;
- event publishing;
- event consuming;
- worker dispatch behavior.

For write flows, assert outbox rows when event publishing is part of the contract.
For consumer flows, publish the input event and verify projection or inbox state.
For worker flows, verify dispatch result and persisted state transitions.

## Boundary

Do not add Kafka assertions when the assigned flow does not involve event behavior.
Do not invent event topics, payload fields, envelope shapes, or generated event artifacts.
Use provided contract/generated event facts when event behavior is part of the case.