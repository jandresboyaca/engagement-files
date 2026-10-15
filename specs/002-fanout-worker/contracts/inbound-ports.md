# Inbound Ports (`application/port/in`)

What the worker offers to its callers. All are plain Java interfaces; implementations live in
`application/service`. None is exposed over HTTP (no endpoint in scope). Errors are explicit:
invalid input throws `IllegalArgumentException` from the value-object constructors; an illegal
state change throws `InvalidTransitionException`. Nothing returns `null`.

## SubmitWorkUseCase (FR-001, FR-013)

```java
SubmitResult submit(SubmitWorkCommand command);

record SubmitWorkCommand(EngagementId engagementId, FirmId firmId, Reason reason) {}
record SubmitResult(WorkItemId workItemId, boolean created) {}
```

| Given | Then |
|---|---|
| No open item (`OUTSTANDING` or `IN_FLIGHT`) for the engagement | A new `OUTSTANDING` item, due now; `created = true` |
| An open item exists | Nothing changes; returns that item's id, `created = false` |
| Only `DONE` or `PARKED` items exist | A new item; `created = true` (US5 scenario 3, FR-012) |

Idempotent under concurrent calls: the database uniqueness rule decides, not a prior read.

## MarkDoneUseCase (FR-003)

```java
boolean markDone(EngagementId engagementId);
```

Closes the open item for the engagement. Returns `true` if an item moved to `DONE`, `false` if
there was no open item (already done, parked, or never added). If the item is `IN_FLIGHT`, the
load is not cancelled: the item keeps its lease, so its slot stays counted against C and F until
the load answers (the answer then only clears the lease) or the lease expires. Publishes
`WorkItemCompleted`.

## RunDispatchCycleUseCase (FR-003 to FR-011)

```java
DispatchResult runOnce();

record DispatchResult(int leasesExpired, int loadsStarted, int claimsSkipped) {}
```

One cycle, in order:
1. Expire `IN_FLIGHT` items whose lease has ended (retry or park, `RetryPolicy`).
2. Read C and F (`CapacitySettingsPort`) and the in-flight counts; compute free slots.
3. `FairnessPolicy` allocates the free slots to firms with due work.
4. For each chosen item: claim it with a conditional save. If the save finds the item is no longer
   outstanding (marked done since it was read), count it in `claimsSkipped` and start no load
   (Clarification 1).
5. Start each claimed load on the injected `Executor`. When it answers, reload the item, apply
   `answer(claimedAttempt, outcome, …)` (data-model.md), and save conditionally on the version
   just read. On a conflict, reload and reapply at most 3 times; then log the conflict (opaque
   ids) and leave the item: its lease expires and step 1 handles it. Publish events after a
   successful save.

Steps 1 and 3 contain no rule of their own: expiry is `WorkItem.expireLease` with `RetryPolicy`,
and allocation is `FairnessPolicy`. The service only orders the calls.

Returns as soon as the loads are started; it does not wait for them. Must not run concurrently
with itself (the scheduler uses a fixed delay).

## ResumeAfterRestartUseCase (FR-015)

```java
int resume();
```

Called by `DispatchScheduler` as the first step of its first tick, before that tick's
`runOnce()`. Returns the number of items in state `IN_FLIGHT` (read through
`WorkItemRepositoryPort.status(now)`) and logs it. It changes nothing: items left in flight by a
previous process keep their slot until their lease ends, and step 1 of the cycle then expires
them like any other timed-out load (research §5).

## GetWorkStatusUseCase (FR-016)

```java
WorkStatus status();

record WorkStatus(
    Map<WorkItemState, Long> countsByState,
    Map<FirmId, Map<WorkItemState, Long>> countsByFirm,
    int loadsInFlight,
    Optional<Duration> oldestOutstandingAge) {}
```

`loadsInFlight` counts unexpired leases, whatever the item's state (an item marked done during
its load still counts). `oldestOutstandingAge` is `now − addedAt` of the oldest
`OUTSTANDING` item, empty if none. Parked items are visible here (FR-012). Firms appear by opaque
id only.
