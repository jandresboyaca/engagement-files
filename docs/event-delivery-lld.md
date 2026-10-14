---
title: Event Delivery (LLD)
description: Low-level design of how one event travels from an existing system to ours
full_width: true
---

> **Low-level design (LLD).** This view goes below the level the exercise asks for. It is kept as
> supporting detail for one line of the design: "no event is lost".

<iframe class="diagram-frame" src="event-delivery.html" title="Event delivery diagram"></iframe>

[Open the diagram full screen](event-delivery.html)

## What each step guarantees

| Step | What happens | Failure it covers |
|---|---|---|
| 1 | The hook writes the business change and the event row in one transaction | A change without an event, or an event for a change that never happened |
| 2-3 | The relay reads unsent rows and publishes them | The broker or our system being down: rows wait in the outbox |
| 4 | The consumer appends the event if its id is new | Duplicates caused by at-least-once delivery or by a replay |
| 5 | The row is marked sent only after the broker acknowledges (not drawn) | An event published but lost before the acknowledgement: it is sent again |

## What happens when something goes wrong

| Failure | Detected by | Repaired by |
|---|---|---|
| Broker loses or delays an event | Sequence gap; published vs consumed counts | Replay from the outbox |
| Same event delivered twice | Processed event ids | Dropped by the consumer |
| Events arrive out of order | Sequence number; broker ordered per key | Consumer waits for or requests the missing one |
| Event cannot be processed (bug, bad data) | Dead-letter queue depth | Fix the cause, redrive from the dead-letter queue |
| Relay is down | Age of the oldest unsent outbox row | Rows stay in the outbox; the relay resumes where it stopped |
| The event row was never written (bug in the emitter) | Not visible to this mechanism | Reconciliation on load, see [To-Be Flows](to-be-sequence.html) |
