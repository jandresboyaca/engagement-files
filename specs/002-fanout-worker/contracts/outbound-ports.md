# Outbound Ports (`application/port/out`)

What the application needs from the outside. Each has one production adapter and one in-memory
fake used by the unit tests. `CapacitySettingsPort` is an addition to the four requested ports
(research D2).

## LoadEngagementPort → `adapter/out/downstream`

```java
LoadOutcome load(WorkItemId workItemId, FirmId firmId, EngagementId engagementId);
```

- Blocking; returns when EMS answers or the read timeout passes. Called on a virtual thread.
- Never throws for a downstream answer or a network error: every case becomes a neutral
  `LoadOutcome` (table below). Throws only for programming errors.
- Translates only. It does not decide success, retry or park: a 2xx status is a success by
  `LoadOutcome.isSuccess()`, and `RetryPolicy` classifies everything else (research §4).
- `workItemId` is sent as the idempotency key; the engagement content is never read; the response
  body is never kept.

| Downstream result (assumed, see ems-load-api.yaml) | `LoadOutcome` | Error code |
|---|---|---|
| Any HTTP status | `Answered(status)` | `HTTP_<status>` (none for 2xx) |
| Read timeout | `TimedOut` | `TIMEOUT` |
| Connection refused or reset, connect timeout | `ConnectFailed` | `CONNECT` |

Fake: answers from a script per engagement id (for example 204, 503 twice then 204, always 404,
time out) and records every call, so tests can count loads per engagement.

## WorkItemRepositoryPort → `adapter/out/persistence`

```java
SubmitResult insertIfNoOpenItem(WorkItem item);
Optional<WorkItem> findOpenByEngagement(EngagementId engagementId);
Optional<WorkItem> findById(WorkItemId id);
InFlightCounts countInFlight(Instant now);                       // leaseUntil > now, any state, total and per firm
List<FirmBacklog> findFirmsWithDueWork(Instant now);             // firm id + oldest due addedAt
List<WorkItem> findOldestDue(FirmId firm, int limit, Instant now);
List<WorkItem> findExpiredLeases(Instant now, int limit);
boolean save(WorkItem item, long expectedVersion);               // false if the row changed
WorkStatus status(Instant now);                                  // FR-016 counts, computed in SQL
```

- `insertIfNoOpenItem` is atomic against concurrent calls (unique partial index).
- `save` writes all fields and increments `version` only if the stored version equals
  `expectedVersion`; it never inserts. A `false` result is normal (the item changed since it was
  read, for example marked done) and is not an error; the caller reloads and decides again.
- Each method is its own transaction; no transaction spans a load.
- `InFlightCounts` and `FirmBacklog` are `domain/policy` records (inputs to the policies);
  `SubmitResult` and `WorkStatus` are `application/dto` records.

Fake: a map guarded by one lock, applying the same uniqueness, due, in-flight and version rules.
One abstract test, `WorkItemRepositoryContract` (test sources, `application/port/out`), states
this whole contract once; it runs against the fake as `FakeWorkItemRepositoryTest` (`mvn test`)
and against the JDBC adapter as `JdbcWorkItemRepositoryIT` (`mvn verify`), so the fake used by the
unit tests cannot drift from the database.

## CapacitySettingsPort → `adapter/out/persistence`

```java
ConcurrencyLimits current();
void initialiseIfAbsent(ConcurrencyLimits fromConfiguration);
```

`current()` is called once per cycle. Fake: a settable field.

## ClockPort → `adapter/out/time`

```java
Instant now();
```

Production: `Clock.systemUTC()`. Fake: a settable, advanceable instant.

## DomainEventPublisherPort → `adapter/out/event`

```java
void publish(DomainEvent event);
```

Production: one structured log line per event with event type, work item id, engagement id,
firm id, attempt and error code: opaque ids only (Principle II). No broker. Fake: a list for
assertions.
