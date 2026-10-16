---

description: "Task list for the template-publish fan-out worker"
---

# Tasks: Template-Publish Fan-out Worker

**Input**: Design documents from `specs/002-fanout-worker/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: Requested (TDD, constitution Principle I). Every test task is RED first:
- A test task also writes the smallest stub that compiles for any production type or method it
  needs that does not exist yet: the class, record or method with a body that returns a default
  value (`0`, `false`, an empty list, map or `Optional`, or its input unchanged), never one that
  throws. RED is an assertion failure against that stub, never a compile error.
- The implementation task that names the test replaces the stub with the minimal code that makes
  it GREEN, then runs the whole suite (`./mvnw test`, or `./mvnw verify` when an `*IT` is
  involved), not only that test.
- Approved exceptions to test-first (by the user): configuration-only files (Dockerfile, compose,
  WireMock mappings, Flyway SQL, properties files). The tests that use them verify them.
- Regression checks by decision, not RED tests: T034 `RestartScenarioIT`, written after the
  restart behaviour exists (its RED cases are in T030), and the two cases in T030 marked as
  regression checks.

**Organization**: Tasks are grouped by user story. Paths are relative to the repository root;
`J` = `fanout-worker/src/main/java/com/caseware/fanout`, `T` =
`fanout-worker/src/test/java/com/caseware/fanout`, `R` = `fanout-worker/src/main/resources`.

**Test doubles** (T008 and T010, in `T/application/fake/`): `FakeLoadEngagement` (scripted
neutral outcomes per engagement id, records calls), `FakeWorkItemRepository` (checked by the same
contract test as the JDBC adapter), `FakeCapacitySettings`, `FakeClock` (settable, advanceable),
`RecordingEventPublisher`, `ManualExecutor` (queues submitted loads; the test decides when each one
answers, so "in flight" is observable without threads).

**Integration tests add work** through `WorkItemRepositoryPort` (`insertIfNoOpenItem`), except
`DeduplicationScenarioIT`, which goes through `SubmitWorkUseCase`. Their WireMock stubs mirror the
files in `fanout-worker/wiremock/mappings/` with delays of milliseconds.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: Which user story this task belongs to (US1 to US5)

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Generate the project exactly as planned (plan.md, "Setup phase, first two tasks").

- [X] T001 At the repository root, run exactly the given `curl … start.spring.io … && unzip … && rm …` command. Change nothing else in that task. Creates `fanout-worker/`.

  ```bash
  curl -sf https://start.spring.io/starter.zip -d type=maven-project -d language=java -d bootVersion=4.1.1 -d javaVersion=21 -d groupId=com.caseware -d artifactId=fanout-worker -d name=fanout-worker -d packageName=com.caseware.fanout -d baseDir=fanout-worker -d dependencies=spring-restclient,jdbc,postgresql,flyway,lombok,configuration-processor,validation,testcontainers,docker-compose -o fanout-worker.zip && unzip -q fanout-worker.zip && rm fanout-worker.zip
  ```

- [X] T002 Edit `pom.xml` (MapStruct, binding, ArchUnit, WireMock, failsafe, processor order), delete `HELP.md`, rename the generated test to `FanoutWorkerApplicationIT`, pin `postgres:latest` in `compose.yaml` and `TestcontainersConfiguration` (research §9, D3).
  - Files: `fanout-worker/pom.xml`, `fanout-worker/HELP.md` (delete), `T/FanoutWorkerApplicationTests.java` → `T/FanoutWorkerApplicationIT.java`, `fanout-worker/compose.yaml`, `T/TestcontainersConfiguration.java`.
  - Versions: `org.mapstruct:mapstruct` and `mapstruct-processor` 1.6.3, `org.projectlombok:lombok-mapstruct-binding` 0.2.0, `com.tngtech.archunit:archunit-junit5` 1.5.1 (test), `org.wiremock:wiremock-standalone` 3.13.2 (test). Annotation processor order in `default-compile`: lombok, lombok-mapstruct-binding, mapstruct-processor, spring-boot-configuration-processor. Bind `maven-failsafe-plugin` goals `integration-test` and `verify` (includes `**/*IT.java`). Pin `postgres:18-alpine`.
  - Done when `./mvnw test` runs no `*IT` and starts no container, and `./mvnw verify` runs `FanoutWorkerApplicationIT`.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Layer rules, Docker files, the work item aggregate, ports with their test doubles,
the repository contract, and persistence. No user story can start before this phase is complete.

- [X] T003 [P] Write `T/architecture/ArchitectureTest.java` with the plan's dependency rules: `domain` depends only on `java.*`; `application` only on `domain` and `java.*`; `adapter.in.*` and `adapter.out.*` depend on `application.port`, `application.dto` and `domain`, never on another adapter or on `application.service`; only `config` (and `FanoutWorkerApplication`) depends on `application.service` and adapters; no `org.springframework`, `lombok`, `org.mapstruct`, `java.sql`, `javax.sql` in `domain` or `application`. Import production classes only. Set `archRule.failOnEmptyShould=false` in `fanout-worker/src/test/resources/archunit.properties` (packages fill up story by story). RED: add a throwaway class in `J/domain/` importing `org.springframework.stereotype.Component`, watch the test fail on it, delete the class, watch it pass.
- [X] T004 [P] Docker files: `fanout-worker/Dockerfile` (stage 1 `eclipse-temurin:21-jdk-alpine` runs `./mvnw -DskipTests package`; stage 2 `eclipse-temurin:21-jre-alpine`, copies the jar, runs as a non-root user); extend `fanout-worker/compose.yaml`: postgres database and user `fanout`, password `${POSTGRES_PASSWORD:-fanout}`; service `wiremock` (`wiremock/wiremock:3.13.2`, mounts `./wiremock`, fixed host port `'8089:8080'`); service `worker` (build `.`, compose profile `app`, depends on both, `SPRING_PROFILES_ACTIVE=local`, `SPRING_DOCKER_COMPOSE_ENABLED=false`, `SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/fanout`, `SPRING_DATASOURCE_USERNAME=fanout`, `SPRING_DATASOURCE_PASSWORD=${POSTGRES_PASSWORD:-fanout}`, `FANOUT_EMS_BASE_URL=http://wiremock:8080`); `fanout-worker/.dockerignore` with `target/` and `.idea/`; make `fanout-worker/mvnw` executable in git with `git update-index --chmod=+x fanout-worker/mvnw` (no `chmod` in the Dockerfile); WireMock mappings in `fanout-worker/wiremock/mappings/`: `load-engagement.json` (`POST /firms/{firmId}/engagements/{engagementId}/load` → 204 after 5000 ms), `load-not-found.json` (engagement `eng-missing` → 404), `load-flaky.json` (engagement `eng-flaky`: WireMock scenario, 503 twice then 204). Verify with `docker compose config` and `docker build .`.
- [X] T005 [P] Write `T/domain/model/ValueObjectsTest.java` (RED): `WorkItemId`, `FirmId`, `EngagementId` reject null; `FirmId` and `EngagementId` are "Opaque, non-blank, ≤ 64 chars"; `Attempt` is "count ≥ 0" and `next()` adds one; `LoadOutcome` is neutral: `Answered(status)`, `TimedOut` or `ConnectFailed`; `isSuccess()` is true only for `Answered` with a 2xx status; `errorCode()` is `HTTP_<status>`, `TIMEOUT` or `CONNECT`.
- [X] T006 [P] Write `T/domain/model/WorkItemTest.java` (RED), one test per transition that does not need `RetryPolicy`: `submit` → `OUTSTANDING`, `attempt = 0`, `nextAttemptAt = addedAt = now`; `claim` only when `OUTSTANDING` and due, `attempt + 1`, `leaseUntil = now + leaseDuration`; `answer(attempt, outcome with isSuccess(), …)` on `IN_FLIGHT` same attempt → `DONE`, lease cleared, `WorkItemCompleted`; `markDone` on `OUTSTANDING` → `DONE`; `markDone` on `IN_FLIGHT` → `DONE` with the lease **kept**; `answer` on `DONE` with lease set and same attempt → lease cleared, state unchanged, no event; `answer` with any other attempt or state → unchanged, never throws; other calls in unlisted states throw `InvalidTransitionException`; `snapshot()` / `restore()` round trip.
- [X] T007 Implement `J/domain/model/` (`WorkItemId`, `FirmId`, `EngagementId`, `Attempt`, `WorkItemState`, `Reason` = `ACTIVATION`, `PUBLISH`, `SWEEP`, `LoadOutcome`, `WorkItem` with nested `Snapshot` record and `pullEvents()`), `J/domain/event/` (`DomainEvent`, `WorkItemCompleted`, `WorkItemParked`), `J/domain/exception/InvalidTransitionException.java`: plain Java, minimal code to pass T005 and T006. Failed answers are added in T032.
- [X] T008 Define the ports and DTOs exactly as in contracts/: `J/application/port/in/` (five use cases), `J/application/port/out/` (`LoadEngagementPort`, `WorkItemRepositoryPort`, `CapacitySettingsPort`, `ClockPort`, `DomainEventPublisherPort`), `J/application/dto/` (`SubmitWorkCommand`, `SubmitResult`, `DispatchResult`, `WorkStatus`), `J/domain/policy/InFlightCounts.java`, `J/domain/policy/FirmBacklog.java`; and the test doubles listed at the top of this file except `FakeWorkItemRepository`, in `T/application/fake/`. Interfaces and records only; no behaviour.
- [X] T009 Write the abstract `T/application/port/out/WorkItemRepositoryContract.java` and `T/application/fake/FakeWorkItemRepositoryTest.java` extending it (RED against a stub `FakeWorkItemRepository`), covering every method of `WorkItemRepositoryPort` per contracts/outbound-ports.md: `insertIfNoOpenItem` returns the existing id for an open item and creates a new one when only `DONE`/`PARKED` exist (FR-012, FR-013); `save` returns `false` on a stale version and increments `version` otherwise; `countInFlight` counts `leaseUntil > now` in any state, total and per firm; `findFirmsWithDueWork` and `findOldestDue` (due only, oldest `addedAt` first); `findExpiredLeases`; `status` (counts by state and firm, in-flight, oldest outstanding age).
- [X] T010 Implement `T/application/fake/FakeWorkItemRepository.java` to pass T009.
- [X] T011 [P] Write `T/adapter/out/persistence/JdbcWorkItemRepositoryIT.java` extending `WorkItemRepositoryContract`, plus database-only cases: the table check constraints reject `IN_FLIGHT` without a lease and `OUTSTANDING`/`PARKED` with one; and `T/adapter/out/persistence/JdbcCapacitySettingsIT.java`: `initialiseIfAbsent` inserts once and never overwrites; `current` reads the row; check rejects `per_firm_limit > global_limit` (RED, Testcontainers PostgreSQL, `@JdbcTest` slice with Flyway).
- [X] T012 Write the Flyway migrations from data-model.md: `R/db/migration/V1__work_item.sql` (columns as listed; `reason` "check in the three reasons"; `state` "check in the four states"; `attempt` "not null, `>= 0`"; `lease_until` "not null when `state = 'IN_FLIGHT'`; null when `state in ('OUTSTANDING','PARKED')`; either when `DONE`"; `engagement_id`, `firm_id` `varchar(64)`; `last_error` `varchar(32)`; indexes `ux_work_item_open_engagement` "unique `(engagement_id)` where `state in ('OUTSTANDING','IN_FLIGHT')`", `ix_work_item_due` "`(firm_id, added_at)` where `state = 'OUTSTANDING'`", `ix_work_item_lease` "`(lease_until)` where `lease_until is not null`") and `R/db/migration/V2__capacity_settings.sql` (`id` "check `id = 1` (one row)", `global_limit` "`>= 0`", `per_firm_limit` "`>= 0`, `<= global_limit`").
- [X] T013 Implement the persistence adapter to pass T011: `J/adapter/out/persistence/WorkItemRow.java`, `WorkItemRowMapper.java` (MapStruct, row ↔ `WorkItem.Snapshot`), `JdbcWorkItemRepository.java` (`JdbcClient`; `INSERT … ON CONFLICT DO NOTHING`; every update `WHERE id = ? AND version = ?`; one transaction per method, none spanning a load), `JdbcCapacitySettings.java`.
- [X] T014 [P] Write tests (RED): `T/adapter/out/event/LoggingDomainEventPublisherTest.java` (plain unit test with a captured logger: each event becomes one log line with event type, work item id, engagement id, firm id, attempt and error code only, Principle II); and extend `T/FanoutWorkerApplicationIT.java`: the `test` profile starts, and startup fails when `fanout.limits.per-firm` exceeds `fanout.limits.global`, when a limit is negative, or when `fanout.load.timeout` is missing (`ApplicationContextRunner` or a failing `SpringApplication` run).
- [X] T015 Implement `J/adapter/out/event/LoggingDomainEventPublisher.java`, `J/adapter/out/time/SystemClockAdapter.java`, `J/config/FanoutProperties.java` (`@ConfigurationProperties("fanout")`, `@Validated`, keys and defaults from plan.md "Configuration"), `R/application.properties` (`spring.threads.virtual.enabled=true`, `spring.main.keep-alive=true`, `fanout.*` defaults), `fanout-worker/src/test/resources/application-test.properties` (millisecond timeout, lease grace, backoff and dispatch period) to pass T014. Adapters are Spring components; services and policies are created only as `@Bean`s in `J/config/`. Run `./mvnw verify`.

**Checkpoint**: domain model, ports, test doubles, repository contract and persistence ready;
`./mvnw verify` green.

---

## Phase 3: User Story 1 - Work through the list without overloading EMS (Priority: P1) 🎯 MVP

**Goal**: Items in the work list are loaded with never more than C loads in flight; C can change
while running.

**Independent Test**: 1,000 items, C = 10, simulated load: all items finish and loads in flight
never exceed 10 (spec US1).

### Tests for User Story 1 (write first, watch them fail)

- [X] T016 [P] [US1] Write `T/domain/policy/ConcurrencyLimitsTest.java`: free global slots = C − in flight (never negative); free slots for a firm = F − its in flight; rejects negative limits and `perFirm > global`; C = 0 gives no free slot (spec edge case: operator pause).
- [X] T017 [P] [US1] Write `T/domain/policy/FairnessPolicyTest.java`, first version: free slots go to firms in order of their oldest due item; never more than the free global slots in total; never more than a firm's free slots (F − its in flight); the item's reason is never used (FR-006). Use only cases where the firms have equal loads in flight, so they stay valid when US2 adds the fewest-in-flight rule.
- [X] T018 [P] [US1] Write `T/application/service/DispatchCycleServiceTest.java`, US1 cases, with the test doubles and `ManualExecutor`: US1 scenario 1 (1,000 items, C = 10: no cycle leaves more than 10 in flight; after answering and further cycles every item is `DONE`; SC-001); US1 scenario 2 (lower C to C' via `FakeCapacitySettings`: no new load until fewer than C' in flight, in-flight loads not cancelled; raise it: effect on the next cycle, SC-006); Clarification 1 (item marked done after being read: `claimsSkipped` + 1, no load; US5 scenario 2); marked done during its load (C = 1: mark the in-flight item done; no new load starts until the fake EMS answers or the lease expires); a save conflict on an answer is reapplied at most 3 times, then left for its lease to expire; the slots per firm are exactly those returned by `FairnessPolicy`.
- [X] T019 [US1] Write `T/adapter/out/downstream/HttpLoadEngagementAdapterIT.java` (in-JVM WireMock): request is `POST /firms/{firmId}/engagements/{engagementId}/load` with `Idempotency-Key: <work item id>`; every status (200, 204, 400, 404, 410, 429, 500, 503) → `Answered(status)` with no classification; read timeout → `TimedOut`; connection refused → `ConnectFailed`; never throws; the response body is not kept (contracts/outbound-ports.md table).
- [X] T020 [US1] Write `T/CapacityScenarioIT.java` (Spring context, `test` profile, Testcontainers PostgreSQL, in-JVM WireMock with a delay of a few hundred ms; items added through `WorkItemRepositoryPort`): with C = 3 and 30 items, the maximum overlap of WireMock serve events (from each request's received and response times) is ≤ 3 and all items end `DONE` (SC-001); with C = 1, marking the in-flight item done starts no second load until WireMock answers; on a fresh database `capacity_settings` holds the configured C and F, and on a database whose row already exists startup does not overwrite it (FR-007).

### Implementation for User Story 1

- [X] T021 [US1] Implement `J/domain/policy/ConcurrencyLimits.java` and the first version of `J/domain/policy/FairnessPolicy.java` (oldest due item first, within free global and per-firm slots) to pass T016 and T017.
- [X] T022 [US1] Implement `J/application/service/DispatchCycleService.java` (`RunDispatchCycleUseCase`), cycle steps 2–5 of contracts/inbound-ports.md, to pass T018: read limits and in-flight counts, ask `FairnessPolicy` for the slots per firm (no allocation logic in the service), claim with a conditional save, start loads on the injected `Executor`, on answer reload, apply `answer(claimedAttempt, outcome, …)`, save conditionally, reapply at most 3 times on conflict and then log, publish events.
- [X] T023 [US1] Implement `J/adapter/out/downstream/HttpLoadEngagementAdapter.java` (`RestClient` on the JDK `HttpClient`, connect timeout `fanout.load.connect-timeout`, read timeout `fanout.load.timeout`, base URL `fanout.ems.base-url`; translates only) to pass T019.
- [X] T024 [US1] Implement `J/adapter/in/scheduler/DispatchScheduler.java` (`@Scheduled` fixed delay `fanout.dispatch.period`, calls `RunDispatchCycleUseCase.runOnce()`), `@EnableScheduling` and the wiring in `J/config/` (`DispatchCycleService`, `FairnessPolicy`, `ConcurrencyLimits` from `CapacitySettingsPort`, `Executors.newVirtualThreadPerTaskExecutor()`, `RestClient`, `capacity_settings` initialised from configuration at startup) to pass T020. Run `./mvnw verify`.

**Checkpoint**: MVP. The worker loads the work list within C, and C changes without a restart.

---

## Phase 4: User Story 2 - A large firm does not block small firms (Priority: P1)

**Goal**: Fair allocation of free slots between firms, within the per-firm cap F.

**Independent Test**: 40,000 items for firm A, then 40 for firm B: B finishes in about the time
it would take alone (spec US2).

### Tests for User Story 2 (write first, watch them fail)

- [ ] T025 [P] [US2] Add to `T/domain/policy/FairnessPolicyTest.java`: each slot goes to the firm with the fewest in flight, counting slots given in this cycle; ties go to the firm whose oldest due item waited longest; a firm with 0 in flight beats an older firm holding F − 1 or F (US2 scenario 2); never more than F per firm (US2 scenario 1); a single firm is still capped at F (US2 scenario 3).
- [ ] T026 [US2] Add US2 cases to `T/application/service/DispatchCycleServiceTest.java`: US2 scenarios 1–3 through the service; SC-002 (C = 20, F = 5, 40,000 items for firm A added first, 40 each for three firms added after; running cycles and answering every in-flight load once per round, each small firm is `DONE` within 9 rounds of being added); in the same run, after every cycle, total in flight ≤ C and each firm's in flight ≤ F (SC-001 at 40,000 items).

### Implementation for User Story 2

- [ ] T027 [US2] Make `J/domain/policy/FairnessPolicy.java` fair (research §3) to pass T025 and T026; T017 and T018 stay green. `DispatchCycleService` does not change.

**Checkpoint**: US1 and US2 pass together.

---

## Phase 5: User Story 3 - Failures are retried, isolated and visible (Priority: P1)

**Goal**: Timeouts and failures retried with growing delay; parked after the maximum or at once
when permanent; one item's failure never delays another; loads in flight at a crash are retried
after their lease.

**Independent Test**: EMS fails one item every time and another twice then succeeds (spec US3).

### Tests for User Story 3 (write first, watch them fail)

- [ ] T028 [P] [US3] Write `T/domain/policy/RetryPolicyTest.java`: classifies neutral outcomes: `TimedOut`, `ConnectFailed`, `Answered` 429, 500, 502, 503 → retryable; `Answered` 400, 404, 410 and any other non-2xx → permanent; next attempt at `now + min(base × 2^(attempt − 1), max)`; park when attempts reach the maximum; permanent parks at once; defaults 30 s, 30 min, 6 attempts.
- [ ] T029 [P] [US3] Add to `T/domain/model/WorkItemTest.java` the transitions that need `RetryPolicy`: `answer(attempt, retryable failure, …)` on `IN_FLIGHT` → `OUTSTANDING`, lease cleared, `nextAttemptAt` from the policy, `lastError` = `errorCode()`; at the maximum → `PARKED` with `WorkItemParked`; permanent → `PARKED`; `expireLease` on `IN_FLIGHT` with `leaseUntil ≤ now` → as a `TIMEOUT` failure; `expireLease` before the lease ends throws `InvalidTransitionException`.
- [ ] T030 [US3] Add US3 and restart cases to `T/application/service/DispatchCycleServiceTest.java`: US3 scenario 1 (retryable failure and timeout via lease expiry in step 1: delay grows per attempt, other items keep being loaded meanwhile, FR-011); US3 scenario 2 (after the maximum, parked, stops taking a slot, visible with its last error); US3 scenario 3 (permanent failure parked without retry); `Answered` 503 and 429 are retryable (FR-009); a firm whose items are all parked does not affect other firms; `DispatchResult.leasesExpired` counts expiries. Restart (FR-015), simulated by a new service over the same fake repository with items left by a "dead" process: US4 scenario 2 (`IN_FLIGHT` with an expired lease loaded once more; with an unexpired lease, and `DONE` with an unexpired lease, still take their slot until the lease ends), RED until T033; plus two regression checks, expected to pass on their first run because the US1 cycle already behaves this way: US4 scenario 1 (`DONE` items never loaded again) and US4 scenario 3 (`OUTSTANDING` items all loaded).
- [ ] T031 [US3] Write `T/RetryParkScenarioIT.java` (Spring context, `test` profile, Testcontainers PostgreSQL, in-JVM WireMock with the `load-flaky` and `load-not-found` stubs in milliseconds, items added through `WorkItemRepositoryPort`): `eng-flaky` gets 3 requests (503, 503, 204) and ends `DONE` with `attempt = 3`; `eng-missing` gets 1 request and ends `PARKED` with `last_error = HTTP_404`; an engagement stubbed to always answer 503 gets `fanout.retry.max-attempts` requests and ends `PARKED` with `HTTP_503`; each retry starts no earlier than its backoff.
- [ ] T032 [US3] Implement `J/domain/policy/RetryPolicy.java` and the failure and expiry transitions in `J/domain/model/WorkItem.java` to pass T028 and T029.
- [ ] T033 [US3] Add cycle step 1 (expire leases via `findExpiredLeases` and `WorkItem.expireLease`) and the failed-answer path to `J/application/service/DispatchCycleService.java`, plus the `RetryPolicy` bean from `fanout.retry.*` in `J/config/`, to pass T030 and T031. Run `./mvnw verify`.
- [ ] T034 [US3] Write the regression test `T/RestartScenarioIT.java` (by decision not RED: the behaviour exists after T033; if it fails, that is a bug to fix with a failing unit test first): Testcontainers PostgreSQL shared by successive application contexts, in-JVM WireMock with a delay of a few hundred ms longer than the test's lease; 10 cycles of start, let loads start, close the context abruptly, start the next context on the same database; assert SC-003: no item lost, no `DONE` item loaded again, each item in flight at a stop loaded at most once more and not before its lease ended, and never more than C concurrent requests across contexts.

**Checkpoint**: US1 to US3 pass together, including restart behaviour.

---

## Phase 6: User Story 4 - Restart without losing place or repeating work (Priority: P1)

**Goal**: At startup the worker reports what a previous process left in flight. The restart
behaviour itself is the normal cycle (US3: T030, T033, T034).

**Independent Test**: start the worker with items left `IN_FLIGHT`: the first tick logs their
count before its cycle, and nothing changes state because of it.

### Tests for User Story 4 (write first, watch them fail)

- [ ] T035 [P] [US4] Write `T/application/service/ResumeAfterRestartServiceTest.java`: `resume()` returns the number of items in state `IN_FLIGHT`, whatever their lease; logs it; changes no item (the fake repository's versions are unchanged).
- [ ] T036 [P] [US4] Write `T/adapter/in/scheduler/DispatchSchedulerTest.java` (plain unit test, fake use cases, no Spring): the first tick calls `resume()` and then `runOnce()`, in that order; later ticks call only `runOnce()`.

### Implementation for User Story 4

- [ ] T037 [US4] Implement `J/application/service/ResumeAfterRestartService.java`, the first-tick hook in `J/adapter/in/scheduler/DispatchScheduler.java`, and the bean in `J/config/` to pass T035 and T036. Run `./mvnw verify`; T034 stays green.

**Checkpoint**: US1 to US4 pass together.

---

## Phase 7: User Story 5 - The same engagement is not loaded twice (Priority: P2)

**Goal**: One open item per engagement; another component can close an item; re-adding after
completion starts a new item.

**Independent Test**: add the same engagement from two triggers: EMS receives one load (spec US5).

### Tests for User Story 5 (write first, watch them fail)

- [ ] T038 [P] [US5] Write `T/application/service/SubmitWorkServiceTest.java`: submit creates an `OUTSTANDING` item due now (FR-001); US5 scenario 1 (adding an engagement that is outstanding or in flight returns the same id with `created = false`); SC-004 (the same engagement from `ACTIVATION`, `PUBLISH` and `SWEEP` gives one item and, after dispatch cycles, exactly one call to `FakeLoadEngagement`); US5 scenario 3 (after `DONE`, re-adding creates a new item that is loaded again); after `PARKED`, re-adding creates a new item (FR-012); invalid ids are rejected by the value objects.
- [ ] T039 [P] [US5] Write `T/application/service/MarkDoneServiceTest.java`: US5 scenario 2 (outstanding item marked done → `DONE`, `WorkItemCompleted` published, and the next cycle does not load it); in-flight item marked done → `DONE` with lease kept (FR-003); no open item → returns `false`, nothing changes.
- [ ] T040 [US5] Write `T/DeduplicationScenarioIT.java` (Spring context, `test` profile, Testcontainers PostgreSQL, in-JVM WireMock): one engagement submitted three times through `SubmitWorkUseCase` (`ACTIVATION`, `PUBLISH`, `SWEEP`) gives one row and exactly one WireMock request (SC-004 end to end).

### Implementation for User Story 5

- [ ] T041 [US5] Implement `J/application/service/SubmitWorkService.java` and `J/application/service/MarkDoneService.java`, and their beans in `J/config/`, to pass T038, T039 and T040. Run `./mvnw verify`.

**Checkpoint**: all five user stories pass together.

---

## Phase 8: Polish & Cross-Cutting Concerns

- [ ] T042 [P] Write `T/application/service/WorkStatusServiceTest.java` (RED): FR-016 counts per state and per firm; `loadsInFlight` counts unexpired leases in any state (an item marked done during its load counts); `oldestOutstandingAge` is `now − addedAt` of the oldest `OUTSTANDING` item, empty if none; parked items appear in the counts (FR-012).
- [ ] T043 Implement `J/application/service/WorkStatusService.java` (`GetWorkStatusUseCase`, delegating to `WorkItemRepositoryPort.status(now)`) and its bean in `J/config/` to pass T042.
- [ ] T044 Local profile: `R/application-local.properties` (`fanout.ems.base-url=http://localhost:8089`, the compose `wiremock` service's fixed host port; the `worker` container overrides it with `FANOUT_EMS_BASE_URL`, `spring.flyway.locations=classpath:db/migration,classpath:db/seed-local`) and `R/db/seed-local/V100__seed_work_items.sql` ("A few outstanding items for three firms of different sizes, including one engagement id that WireMock answers with 404 (parked at once) and one that answers 503 twice and then succeeds": use `eng-missing` and `eng-flaky` from T004).
- [ ] T045 Run the full suite and the local stack: `./mvnw verify` all green with no warnings from our own code (accepted third-party warnings, to be listed in the README: the Byte Buddy dynamic agent notice and the Flyway extension repositories returning 401); then quickstart.md §3: `docker compose --profile app up --build` shows loads within C and F, `WorkItemCompleted` for most seeded items, `eng-missing` parked with `HTTP_404`, `eng-flaky` completed after two retries, the first tick's resume count in the log, and an `UPDATE capacity_settings` taking effect on the next cycle; logs carry opaque ids only. Record any failure by name.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (T001–T002)**: T001 then T002.
- **Foundational (T003–T015)**: after Setup; blocks every story. T007 needs T005 and T006 RED; T008 needs T007; T009 needs T008; T010 needs T009 RED; T011 needs T010 (it extends the contract); T013 needs T011 RED and T012; T015 needs T013 and T014 RED.
- **US1 (T016–T024)**: after Foundational.
- **US2 (T025–T027)**: after US1 (it extends the US1 `FairnessPolicy`).
- **US3 (T028–T034)**: after US1. Both US2 and US3 edit `DispatchCycleServiceTest.java`: do them one after the other. T034 after T033.
- **US4 (T035–T037)**: after US3 (T037 keeps T034 green).
- **US5 (T038–T041)**: after US1 (SC-004 needs dispatch). Can run alongside US2 to US4: different files.
- **Polish (T042–T045)**: T042–T043 after Foundational; T044 after T004; T045 last.

### Within Each User Story

- Unit test tasks, then integration test tasks, each RED (against stubs) before any
  implementation task; T034 is the one regression test, written after its behaviour exists.
- An implementation task is done only when the tests it names pass and the rest of the suite
  stays green.

### Parallel Opportunities

- Foundational: T003, T004, T005, T006 together; T011 and T014 together once T010 is done.
- US1: T016, T017 and T018 together.
- US2/US3: T025, T028, T029 together (different files).
- US4: T035 and T036 together.
- US5 (T038, T039) alongside US2 to US4.
- Polish: T042 at any time after Foundational.

## Parallel Example: User Story 1

```bash
Task: "T016 Write T/domain/policy/ConcurrencyLimitsTest.java"
Task: "T017 Write T/domain/policy/FairnessPolicyTest.java, first version"
Task: "T018 Write T/application/service/DispatchCycleServiceTest.java, US1 cases"
```

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Setup (T001–T002), Foundational (T003–T015).
2. US1 (T016–T024). **Stop and validate**: `./mvnw verify`; the worker loads within C.

### Incremental Delivery

US2 (fairness) → US3 (failures and restart) → US4 (startup report) → US5 (deduplication) →
Polish, validating with `./mvnw verify` at every checkpoint.

## Traceability

| Spec / quickstart item | Test task(s) |
|---|---|
| US1 scenarios 1, 2 | T018, T020 |
| US2 scenarios 1–3, SC-002 | T025, T026 |
| US3 scenarios 1–3, FR-009 overload | T028, T029, T030, T031 |
| US4 scenarios 1–3, FR-015 | T030 (RED), T034 (regression); startup report T035, T036 |
| SC-003 (10 restarts) | T034 |
| US5 scenario 1, SC-004 | T038, T040 |
| US5 scenario 2 | T018, T039 |
| US5 scenario 3, FR-012 | T009, T038 |
| Marked done during its load keeps its slot | T006, T018, T020 |
| SC-001 | T018, T020, T026 |
| SC-006, FR-007 | T018, T020 |
| FR-013 contract on fake and database | T009, T011 |
| Edge cases: cap 0, all of a firm parked | T016, T030 |
| Configuration validation | T014 |
| quickstart: `WorkItemTest`, `ConcurrencyLimitsTest`, `FairnessPolicyTest`, `RetryPolicyTest` | T006/T029, T016, T017/T025, T028 |
| quickstart: `ResumeAfterRestartServiceTest`, `SubmitWorkServiceTest`, `WorkStatusServiceTest` | T035, T038, T042 |
| quickstart: `ArchitectureTest` | T003 |
| quickstart: `JdbcWorkItemRepositoryIT`, `HttpLoadEngagementAdapterIT` | T011, T019 |
| quickstart: `RestartScenarioIT`, `CapacityScenarioIT`, `FanoutWorkerApplicationIT` | T034, T020, T014 |
| quickstart §3 local stack | T045 (manual run, not an automated test) |

## Notes

- Out of scope and deliberately absent: REST endpoints, broker, cloud SDK, infrastructure code,
  Kubernetes, CI, metrics stack, the components that add work or record results, adaptive
  slow-down, pausing during an outage, several instances, an operator interface, README.
- Commit after each GREEN task or logical group.
