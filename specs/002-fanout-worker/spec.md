# Feature Specification: Template-Publish Fan-out Worker

**Feature Branch**: `002-fanout-worker`

**Created**: 2026-10-14

**Status**: Draft

**Input**: User description: "Specify the template-publish fan-out worker: Part 2 of
`docs/Staff_Java_Developer_-_Take-Home_Test 2.md`. What: a background worker that goes through a
list of engagements that still need to be loaded and asks the Engagement Management System to load
each one. Other components add work to that list (a firm being activated, a template publish, a
periodic sweep); the worker does not decide what needs loading. Why: one load takes about a minute
and uses capacity that another team owns and live users share. One firm can have about 40,000
engagements while the median has about 40. Without control the worker would overload that team, let
one large firm block all the small ones, lose its place after a crash, or load the same engagement
twice. Cover, as testable behaviour, concurrency, failure, idempotency and downstream capacity. Out
of scope: recording what a load found, the user interface, summaries, event delivery, and deciding
which engagements need loading."

**Source**: `docs/Staff_Java_Developer_-_Take-Home_Test 2.md` (cited as [P] The Problem, [S] Systems
You Can Build On, [Part 2]) and the Part 1 design, `docs/design.md` (cited as [D]). This document
says WHAT and WHY only.

## Clarifications

### Session 2026-10-14

- Q: When should the worker consider an item done? → A: When EMS answers that the load succeeded,
  or when another component marks the outstanding item done. Before starting a load, the worker
  re-checks that the item is still outstanding.
- Q: After a load times out, when may its slot be used again, including for that item's retry?
  → A: At the timeout. The timeout is set above the longest time EMS can take for a load, so a
  timed-out load is assumed to have stopped.
- Q: Should publish items be loaded before activation or sweep items? → A: No. All reasons are
  treated alike; only fairness between firms decides the order.

## User Scenarios & Testing *(mandatory)*

The "user" of this component is the operator of the pending-updates system and, indirectly, the
team that owns Engagement Management (EMS). Every story is tested against a simulated EMS whose
load duration, failures and answers are controlled by the test.

### User Story 1 - Work through the list without overloading EMS (Priority: P1)

Items added to the work list are loaded one by one, with no more loads in flight than EMS allows.

**Why this priority**: It is the component's purpose, and the downstream "is owned by another team
with limited capacity" [Part 2]; one load takes ~1 minute, a hard constraint [S].

**Independent Test**: Add 1,000 items with the cap set to 10 and a simulated 1-minute load. All
items finish, and loads in flight never exceed 10.

**Acceptance Scenarios**:

1. **Given** N items in the list and a global cap C, **When** the worker runs, **Then** at most C
   loads are in flight at any moment and every item reaches a final state.
2. **Given** the worker is running at cap C, **When** the operator lowers the cap to C' < C,
   **Then** no new load starts until fewer than C' are in flight; in-flight loads are not
   cancelled. Raising the cap takes effect the same way, without a restart.

---

### User Story 2 - A large firm does not block small firms (Priority: P1)

**Why this priority**: The largest firm has ~40,000 engagements, the median ~40 [P]. Taken in
order of arrival, one large firm would hold every slot for days.

**Independent Test**: Add 40,000 items for firm A, then 40 items for firm B. Firm B finishes in
roughly the time it would take alone, not after firm A.

**Acceptance Scenarios**:

1. **Given** several firms with outstanding items, **When** loads are started, **Then** no firm
   has more than its per-firm cap F in flight, with F < C.
2. **Given** firm A holds its full share and firm B's items are added, **When** a slot frees up,
   **Then** firm B's next item starts before firm A's next item.
3. **Given** only one firm has outstanding items, **When** the worker runs, **Then** that firm is
   still limited to F (unused capacity stays with EMS and its live users).

---

### User Story 3 - Failures are retried, isolated and visible (Priority: P1)

**Why this priority**: A one-minute remote call will time out and fail. One bad engagement must
not stop the list.

**Independent Test**: Make the simulated EMS fail one item every time and fail another twice then
succeed.

**Acceptance Scenarios**:

1. **Given** a load fails with a retryable error or times out, **When** it is retried, **Then** the
   delay grows with each attempt and other items keep being processed meanwhile.
2. **Given** an item has failed the maximum number of attempts, **When** it fails again, **Then**
   it is parked, stops consuming capacity, and is visible to the operator with its last error.
3. **Given** a load fails with an error EMS marks as permanent (e.g. engagement not found),
   **When** the result arrives, **Then** the item is parked without further retries.

---

### User Story 4 - Restart without losing place or repeating work (Priority: P1)

**Why this priority**: The worker runs for days during backfill [D]; it will be restarted and
redeployed. There is no maintenance window [S].

**Independent Test**: Stop the worker abruptly while loads are in flight, restart it, and compare
the loads EMS received with the list.

**Acceptance Scenarios**:

1. **Given** items that finished before the crash, **When** the worker restarts, **Then** none of
   them is loaded again.
2. **Given** items that were in flight at the crash, **When** the worker restarts, **Then** each is
   loaded again at most once more, after the timeout has passed.
3. **Given** items not yet started, **When** the worker restarts, **Then** all are processed; none
   is lost.

---

### User Story 5 - The same engagement is not loaded twice (Priority: P2)

**Why this priority**: Each duplicate load wastes a minute of another team's capacity. Three
triggers can add the same engagement independently [D].

**Independent Test**: Add the same engagement from two triggers; EMS receives one load.

**Acceptance Scenarios**:

1. **Given** an engagement already outstanding or in flight, **When** it is added again, **Then**
   no second item is created and only one load occurs.
2. **Given** an outstanding item is marked done by another component (e.g. a user opened the
   engagement) [D], **When** the worker reaches it, **Then** it is not loaded.
3. **Given** an engagement whose earlier item finished, **When** a trigger adds it again, **Then**
   it is loaded again: deciding whether a load is needed belongs to the trigger, not the worker.

---

### Edge Cases

- A load succeeds at EMS but the answer is lost (timeout, crash): the item is retried, so EMS may
  see that engagement twice. This is the only accepted duplicate (FR-015).
- An item is completed by another component while its load is in flight: the load is not
  cancelled; the item stays done.
- The cap is set to 0: no new loads start (operator pause); in-flight loads finish.
- A firm's items are all parked: other firms are unaffected.
- An item names an engagement or firm EMS does not know: permanent failure, parked.

## Requirements *(mandatory)*

### Functional Requirements

**Work list**

- **FR-001**: Other components MUST be able to add items (engagement, firm, reason: activation,
  publish or sweep). The worker MUST NOT create items itself.
- **FR-002**: Each item MUST be in exactly one state: outstanding, in flight, done, or parked.
- **FR-003**: An item MUST become done when EMS answers that its load succeeded, or when another
  component marks the outstanding item done. Immediately before starting a load, the worker MUST
  re-check that the item is still outstanding and MUST NOT load it otherwise.

**Concurrency and downstream capacity**

- **FR-004**: Loads in flight MUST NOT exceed the global cap C.
- **FR-005**: Loads in flight for one firm MUST NOT exceed the per-firm cap F.
- **FR-006**: A firm with outstanding items MUST get a free slot within a bounded wait that does
  not depend on the size of other firms' backlogs. An item's reason (activation, publish or sweep)
  MUST NOT affect when it is loaded.
- **FR-007**: C and F MUST be changeable at runtime without a restart.

**Failure**

- **FR-009**: A load with no answer within a configurable timeout, or answered by EMS as
  overloaded, MUST be treated as a retryable failure. A load that has not answered MUST keep its
  slot (counted against C and F) until the timeout; from then on the slot is free for any load,
  including that item's retry.
- **FR-010**: Retryable failures MUST be retried with a growing delay up to a configurable
  maximum of attempts; then the item MUST be parked with its last error. Permanent failures MUST
  be parked at once.
- **FR-011**: A failure of one item MUST NOT delay other items.
- **FR-012**: Parked items MUST stay visible through the counts in FR-016. Re-adding the engagement
  through FR-001 starts a new item.

**Idempotency and restart**

- **FR-013**: At most one item MUST be outstanding or in flight per engagement.
- **FR-015**: Progress MUST survive a crash: after restart, done items are not reloaded, not-started
  items are not lost, and an item in flight at the crash is loaded at most once more.

**Operations**

- **FR-016**: The worker MUST expose counts per state and per firm, loads in flight, and the age of
  the oldest outstanding item.
- **FR-017**: Logs and metrics MUST identify engagements and firms by opaque id only (constitution
  II).

### Key Entities

- **Work item**: one engagement to load: engagement id, firm id, reason, state, attempts, next
  attempt time, last error.
- **Capacity settings**: global cap C, per-firm cap F, load timeout (above the longest time EMS can
  take for a load), maximum attempts. Owned by the operator in agreement with the EMS team.

## Success Criteria *(mandatory)*

All measured against the simulated EMS.

- **SC-001**: In a run of 40,000 items, loads in flight never exceed C, and per firm never exceed F.
- **SC-002**: With C = 20 and F = 5, 40,000 items for one firm added first and 40 items each for
  three other firms added after, each small firm finishes within 9 load durations of being added
  (⌈40 / F⌉ + 1), independent of the large firm's backlog.
- **SC-003**: Across 10 abrupt restarts during a run, no item is lost, no done item is loaded again,
  and extra loads are no more than the loads in flight at each crash.
- **SC-004**: With the same engagement added by all three triggers, EMS receives exactly one load
  for it.
- **SC-006**: A change to C takes effect for the next load started, with no restart.

## Assumptions

- EMS exposes a way to request the load of one engagement and answers with success or failure.
  [S: "you can add hooks or events to any actions taken by ... the engagement management system"]
- The load has no lasting effect on the engagement other than cost, so a repeated load is wasteful
  but harmless (see Open Question 2).
- The values of C and F are set by the team that owns EMS capacity [D]; the source gives no number.
- A load that has not answered within the timeout has stopped at EMS, because the timeout is set
  above the longest time EMS can take for a load. Its value is agreed with the EMS team.
- The worker runs per region and only sees its region's list [D]; residency is otherwise not its
  concern.
- One worker instance runs per region.
- What a load found is recorded by another component [D]; the worker only requests the load.
  Recording it is out of scope.

## Out of Scope

- Adaptive slow-down when EMS reports overload; an overload answer is a retryable failure (FR-009).
- Pausing new loads during an EMS outage; during an outage, items use up their attempts and may
  be parked.
- Running several worker instances; one instance per region is assumed.
- An operator interface, including requeuing parked items; a parked engagement is retried by
  re-adding it (FR-001, FR-012).

## Open Questions

1. Does EMS tell callers it is overloaded (e.g. a rejection or a "retry later" answer), or must the
   worker infer it from timeouts and errors? Either way, adaptive slow-down is out of scope: an
   overload answer is a retryable failure (FR-009).
2. Does a load triggered by the worker have any side effect on an open user session or on the
   engagement's stored state? If so, duplicate loads are not harmless.
