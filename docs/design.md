---
title: Design
description: Pending template updates. Architecture, correctness, scale, summaries and trade-offs
---


**Scope.** Show each firm which of its engagement files have a pending template update, with a
human-readable summary of the change, so the user can apply or decline. Applying template content
is out of scope. Diagrams are published separately.

**The main constraint.** Reading an engagement's template version requires
loading it: about one minute, on pods shared with live users. With 800,000 engagements that read
cannot be part of anything the user waits for. The design keeps its own index, fed by events, and pays the
one-minute load once per engagement, in the background.

## 1. Architecture and data ownership

**Two zones.** The global zone holds template data only, identical for every firm. The regional
zone is deployed in full once per region (EU, Canada, rest) and holds everything that identifies
a firm, an engagement or a user. Regional data never leaves its region; template data flows one
way, global to regional.

| New component | Zone | Responsibility |
|---|---|---|
| Summary Service, Summaries DB | Global | One summary per version transition, immutable, with provenance |
| Event Broker | Regional | Delivers events at least once; has a dead-letter queue |
| Reconciler | Regional | Consumes events, drops duplicates, keeps the index and the audit trail |
| Pending Updates DB | Regional | Engagement events, index, copies of versions and summaries, work list |
| Query API | Regional | Returns pending flags and summaries to the existing engagement UI |
| Fan-out Worker | Regional | Requests loads for engagements the index does not know yet |

**What we ask from the existing systems** is five events, not a mechanism.

| System | Events | Also |
|---|---|---|
| Template Storage | `TemplatePublished`, `TemplateWithdrawn` | A one-time listing of the lineage of versions already published |
| Engagement Management | `EngagementCreated`, `EngagementLoaded`, `UpdateDecided` | Its UI calls our Query API |

They are fat events in a CloudEvents envelope: metadata only, with an event id and a per-emitter
sequence. How each system emits them is its own decision; we require that none is lost and
recommend an outbox written in the same transaction as the change. Template events go to the
Summary Service and to every region; engagement events stay in their region.

**Ownership.**

| Data | Owner |
|---|---|
| Template versions and lineage (parent, market, withdrawn) | Template Storage. It reaches us only inside the events and the one-time listing |
| Engagement content and the effect of applying or declining | Engagement Management |
| Current template version per engagement, and the audit trail | Reconciler, in the Pending Updates DB |
| Summary text and how it was produced | Summary Service, in the Summaries DB |

We never read another system's database, and the two new databases are not connected to each
other.

**Inside the Pending Updates DB.** `engagement_events` is append-only and is the source of truth
and the audit trail. `engagement_index` (one row per engagement), `template_versions` and
`summaries_copy` are derived and can be rebuilt. "Pending" is not stored: it is a query that joins
the index with the copy of the versions. A publish is therefore one event, not one write per
engagement.

On AWS: SNS with one SQS FIFO queue per consumer, PostgreSQL per region, Bedrock for inference.
The model sees template content only, so it can run in any region.

## 2. Correctness and production evolution

**Lifecycle with non-linear versions.** An engagement belongs to one market branch. Its target is
the latest active version reachable from its current version in that branch.

| Situation | Behaviour |
|---|---|
| Several updates accumulate | One offer, the target; the summary covers current to target and lists the versions in between |
| User declined v5, then v7 is published | Declining pins only v5. v7 is offered on its own, and its summary says it includes the changes declined in v5 |
| A pending version is withdrawn | It stops being offered; the target is recomputed |
| An applied or declined version is withdrawn | The record is kept; the user is told and offered the next valid version |
| A version is published on another branch | Not offered to this engagement |

Because pending is computed on read, these rules are in one query and can change without
migrating data.

**Unreliable delivery.** Delivery is at-least-once. The Reconciler records processed event ids,
so a duplicate is dropped, and uses the sequence to see gaps. Reconciliation works at three
levels:

1. **Outbox against consumed.** Continuous, no loads. A lost event is found by counts and
   sequence gaps and repaired by replay from the outbox.
2. **On load.** Every time a user opens an engagement, `EngagementLoaded` carries the real
   version and the Reconciler compares it with the index. This is free, and it catches the one
   failure level 1 cannot see: an event that was never written.
3. **Background sweep.** The worker forces loads for engagements nobody opens.

A mismatch appends a `Reconciled` event. Nothing is overwritten.

**Existing engagements, with no maintenance window.**

1. The existing systems start publishing the five events. Nothing depends on them yet.
2. The lineage of all versions already published is loaded into each region.
3. Firms are activated one by one, small firms first, several in parallel with a quota per firm.
   The Fan-out Worker loads every engagement of the firm; an engagement a user opens meanwhile is
   indexed for free and leaves the work list.
4. The feature is switched on for a firm only when all its engagements are indexed. The user sees
   two states, pending or nothing, never a half-known one.

Rollback is the per-firm switch. The existing systems keep working if ours is down.

**The Fan-out Worker** is one mechanism for three triggers: firm activation, a publish (for
engagements not yet indexed) and the background sweep. It never writes the index: its load makes
Engagement Management publish `EngagementLoaded`, the same path a user load takes.

## 3. Scale, cost and operations

**The one-minute load.**

| Quantity | Arithmetic | Result |
|---|---|---|
| Full backfill | 800,000 × 1 min | 13,333 pod-hours |
| Concurrency to finish in 30 days | 800,000 ÷ (30 × 1,440) | about 19 loads in flight |
| Largest firm, quota of 20 | 40,000 ÷ 20 | about 33 hours |
| One publish, if it needed loads | 800,000 ÷ 40 = 20,000 × 1 min | 333 pod-hours; 6.7 h at 50 concurrent |

The last row is why "within seconds" cannot depend on the worker. After backfill a publish needs
no loads: one event updates the regional copy of versions and the next read shows the flag.

**Steady state.**

| Flow | Arithmetic | Result |
|---|---|---|
| Publishes | 40 products × 1 per week | about 173 per month |
| Decisions, upper bound | 40 × 20,000 per week | 800,000 per week, 1.3 per second |
| Load events | each engagement opened once per working day | about 9 per second |
| Event rows | 800,000 registered, then up to 800,000 decisions per week, about 0.5 KB each | about 21 GB per year |

A load that matches the index appends nothing. These rates are small for a queue and a
relational database.

**Inference.** One summary per version transition, not per engagement.

| | Arithmetic | Summaries per month |
|---|---|---|
| This design (up to 10 versions in use) | 173 × 10 | 1,730 |
| One per engagement | 173 × 20,000 | 3,460,000 |

At about 45,000 tokens per summary that is about 78 million tokens per month. Cost
is 78 × p, with p the blended price per million tokens; at an illustrative p of $10, about $780.
The per-engagement alternative is 2,000 times larger.

**Against «$X».** The bill has two parts: a fixed part (one global and three regional stacks,
each a small database, queues and containers) and the inference line. Neither grows with the
number of engagements.

**Service levels and alerts.**

| Signal | Target or alert |
|---|---|
| Publish to flag visible, activated firms | p99 under 10 seconds |
| Publish to summaries available | p95 under 2 minutes |
| Query API latency | p99 under 300 ms |
| Age of the oldest unsent outbox row | alert above 30 seconds |
| Dead-letter queue depth | alert above 0 |
| `Reconciled` events per 1,000 loads | alert on a sustained rate after backfill |

**Failure recovery.** Broker down: events wait in the outbox. Regional database lost: restore
`engagement_events` and rebuild the index and the copies. Global zone down: regions keep serving
from their copies. Worker crash: it resumes from its work list without repeating finished loads.

## 4. Summaries: generation and evaluation

**Generation.** On `TemplatePublished` the Summary Service takes the JSON diff from the existing
diff capability and writes the parent-to-new summary. On the same event the Reconciler of each
region asks for one summary per version its engagements are on and keeps a copy, so nothing is
generated while a user waits.

**Evaluation.** The model returns a list of change items, each citing the diff paths
it describes; the prose is rendered from that list. Three checks are then deterministic:

1. **Coverage.** Every path in the diff is cited by at least one item.
2. **No invention.** No item cites a path that is not in the diff.
3. **Values.** Every number or name in the text equals the value in the diff.

A summary that fails is marked `needs_review`, and the user sees a rule-based listing of the diff
with a notice. The flag is never blocked by a summary. A fixed set of past diffs with
human-written summaries is replayed on every prompt or model change.

**Defending a summary in November.** Each summary is stored with the exact diff used, its hash,
the model, the prompt version and the validation result. Summaries are immutable; a regenerated
one is a new row with a new id. `UpdateDecided` carries the id of the summary the user was shown,
the user and the time, and is appended to `engagement_events`. We can show what the user saw, what
it was derived from, and that it passed the checks in force that day.

## 5. Trade-offs, assumptions, and the requirement I would challenge

| Choice | Instead of | Cost accepted |
|---|---|---|
| Own index fed by events | Making engagement storage queryable | Eventual consistency; five events from other teams |
| Lineage stays with Template Storage | A separate version catalog service | More asked from that team; the catalog is plan B |
| The contract is the event; outbox recommended | A webhook to our system | We depend on how well each team emits |
| Strict residency, three full regional stacks | Centralising ids and versions | Three times the operations; no global view |
| Summary per transition, global | Summary per engagement | It cannot say how a change affects one particular file |
| Firm-by-firm activation | A third "being verified" state | Large firms get the feature last |

**Assumptions.** A loaded engagement exposes its current version, not only the creation one.
Engagement ids of a firm, with their product, can be listed without loading them. An outbox table
can be added to the template database and to the customer databases. Template Storage knows the
parent and market of every version, including those already published. The diff works between
non-consecutive versions. The team that owns the pods sets our concurrency limit.

**The requirement I would challenge: "real time, within seconds of publish".**
Templates change about once a week per product and the decision is a human one that takes days.
The design meets it for the flag at no extra cost. Applied to the summary it works against the
requirement that matters more: a summary shown seconds after publish has had no human look. I
would ask for the flag in seconds and the summary released after validation, with content-team
approval for changes marked high-impact.

**Riskiest part.** The index being silently wrong: nothing shown for an engagement
that has an update. A wrong summary is visible and reviewable; a missing flag is not. It rests on
the first two assumptions. Mitigations: the activation gate, reconciliation on every load, and the
alert on `Reconciled` events.

**Deliberately left out.** Applying template content. A screen of our own. Who is
allowed to decide. Notifying users by email or push when an update appears; they see it when they
open their list. Per-engagement impact analysis, which would need confidential
content and a load. A streaming platform. AWS sizing and prices.
