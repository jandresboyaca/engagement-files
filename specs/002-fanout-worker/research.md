# Research: Template-Publish Fan-out Worker

**Feature**: `002-fanout-worker` | **Date**: 2026-10-14 | **Spec**: [spec.md](spec.md)

Each entry gives the decision, the reason, the rejected alternative, and a **Relies on** line
naming only what was actually consulted for it (constitution, Technology & Architecture
Constraints). Entries marked
**Disagreement** depart from a technical choice given in the planning request; the request asked
for that to be said here rather than changed silently.

## Disagreements with the planning request

| # | Requested | Planned instead | Why, in one line |
|---|---|---|---|
| D1 | One `Semaphore` for the global limit and one per firm | In-flight count derived from the work list (leases), read each dispatch cycle | Clarification 2 says a slot stays taken until the timeout even across a crash; in-memory permits are lost on restart (§1) |
| D2 | Four outbound ports | A fifth, `CapacitySettingsPort` | FR-007 needs a way to change C and F while running; with no endpoint and no Spring Cloud, a settings row is the simplest (§2) |
| D3 | Initializr output kept as generated, apart from HELP.md | The generated `FanoutWorkerApplicationTests` is renamed `FanoutWorkerApplicationIT`, failsafe is bound, and `postgres:latest` is pinned in the generated test config too | The generated test starts a container under `mvn test`, which breaks "unit tests start no Spring context" (§9) |

Everything else in the request is followed as given.

## 1. Concurrency limit (global C, per firm F)

**Decision**: The work list is the single source of truth for what is in flight. Claiming an item
sets `state = IN_FLIGHT` and `lease_until = now + load timeout + grace`. The lease is cleared only
when the load answers or the lease expires, never by a state change alone: an item marked done
during its load keeps its lease, because EMS is still running that load. At the start of every
dispatch cycle the worker counts rows with `lease_until > now`, whatever their state, in total
and per firm, and `ConcurrencyLimits` (pure domain) turns those counts into free slots. Only one
dispatch cycle runs at a time (`@Scheduled` fixed delay, one instance per region), so nothing can
claim between the count and the claim. Loads run as blocking calls on Java 21 virtual threads.

**Reason**:
- Clarification 2: a load that has not answered keeps its slot until the timeout. After a crash
  EMS may still be running the loads the dead process started; a lease row still counts them, a
  fresh `Semaphore` does not, so the restarted worker would start up to C more loads at once.
- FR-007 becomes trivial: limits are read at the start of each cycle; nothing has to be resized.
- One mechanism covers three cases: a hung call, a crash, and a normal timeout all end with an
  expired lease, handled by the same domain rule (FR-009, FR-015).
- Counting by lease rather than by state keeps the cap when another component marks an item done
  during its load (FR-003): the load is not cancelled, so its slot must stay taken until it
  answers (FR-004, FR-005).
- Testable without threads: the unit tests give the fake repository some in-flight items and
  assert how many are claimed.

**Rejected alternatives**:
- *Two `Semaphore`s (requested).* Correct within one process, but permits reset on restart
  (above). Resizing also needs a subclass (`reducePermits` is protected), and taking a global
  permit and then failing the firm permit needs careful release ordering.
- *Counter under a lock (allowed by the request as a fallback).* Fixes resizing, not restart.
- *Seeding an in-memory gate from the database at startup.* Correct, but two sources of truth that
  must agree; the lease count alone is simpler.

**Cost accepted**: one indexed count query per cycle (cycle period ~1 s, see §6). Correctness
relies on a single dispatcher, which the spec assumes (one instance per region; several instances
are out of scope). Moving to several instances would need claims with `FOR UPDATE SKIP LOCKED`
and a count inside the same transaction.

**Virtual threads, not reactive**: the load is ~19 to 50 concurrent one-minute calls [D: "about
19 loads in flight" for a 30-day backfill; "6.7 h at 50 concurrent"]. The scarce resource is the
downstream team's capacity, not our threads; a virtual thread per call costs almost nothing while
it waits. The reactive stack (WebClient, R2DBC) was **rejected**: it would replace JDBC and
Flyway (R2DBC has no Flyway support; migrations would need a second JDBC path), spread
`Mono`/`Flux` through the application layer, which must stay plain Java, and make the fairness
rule and the restart tests harder to read and to make deterministic.

**Virtual-thread details that matter**:
- No database connection is held during the one-minute call: claim (short transaction), call,
  then record the outcome (short transaction). The connection pool size stays independent of C.
- `spring.main.keep-alive=true` is required: with virtual threads enabled and no web server,
  every application thread is a daemon thread, and the JVM would exit right after startup.
- Pinning on `synchronized` (fixed only in Java 24) is not a concern: the PostgreSQL driver and
  HikariCP use `ReentrantLock`, and our code holds no lock while calling out.

**Relies on**: spec Clarification 2 (slot held until the timeout) and the assumption of one
instance per region; spec FR-003 to FR-007; `docs/design.md` (load figures quoted above); the
planning request (virtual threads, semaphores as the starting point). The keep-alive and pinning
details are prior knowledge, not checked against library documentation in this session; the
context-load IT confirms keep-alive in Setup. No reference book or reference scenario from the constitution was consulted for this decision.

## 2. Changing limits while running (FR-007)

**Decision**: C and F live in a one-row table `capacity_settings`. At startup the row is inserted
from configuration (`fanout.limits.global`, `fanout.limits.per-firm`) only if it does not exist.
Each dispatch cycle reads it through `CapacitySettingsPort`. The operator changes the limits with
one SQL `UPDATE`; it takes effect on the next cycle (SC-006). Timeout, attempts and backoff stay in
static configuration.

**Reason**: Spring Boot binds `@ConfigurationProperties` once; without Spring Cloud refresh or an
endpoint (both out of scope) there is no in-process way to change them. A row works the same under
docker compose and in tests, and an agreed lower limit for EMS survives a restart.

**Rejected alternatives**:
- *JMX operation (inbound adapter + `ChangeLimitsUseCase`).* No dependency needed, but awkward to
  reach in a container, and the change is lost at restart, silently restoring a higher limit.
- *Spring Cloud refresh scope.* Out of scope (no Spring Cloud in this module) and needs an
  endpoint or a bus.
- *Watching a file.* Custom code for something the database already gives us.

**Cost accepted**: a fifth outbound port (D2). Note for operators: once the row exists,
configuration changes to C and F are ignored until the row is updated or deleted.

**Relies on**: spec FR-007 and SC-006; the planning request (no endpoint, no Spring Cloud). That
`@ConfigurationProperties` are bound once is prior knowledge of Spring Boot, not checked against
its documentation in this session. No reference book or reference scenario from the constitution was consulted for this decision.

## 3. Fairness between firms (FR-005, FR-006)

**Decision**: Each cycle the repository returns one row per firm with due outstanding work: the
firm id and the time its oldest due item was added. `FairnessPolicy` (domain) hands out the
free global slots one at a time, always to the firm with the fewest loads in flight (counting
slots given in this cycle), skipping firms at F; ties go to the firm served least recently, then
to the firm whose oldest due item has waited longest. When each firm was last served is kept in
memory by the policy and starts empty after a restart. The repository then returns each chosen
firm's oldest due items, up to its allocation, oldest first (planning request: oldest first within
a firm). The reason (activation, publish, sweep) is not used (Clarification 3).

**Reason**: a firm with no loads in flight always ranks ahead of a firm holding loads, and a firm
not served yet ranks ahead of every firm already served. With N firms having due work, every firm
is served at least once every ⌈N / C⌉ rounds (one round is one load duration), up to F at once
when fewer firms compete. A firm with n items therefore finishes within about n × ⌈N / C⌉ rounds,
and within ⌈n / F⌉ + 1 rounds when N × F ≤ C (SC-002). The bound depends on how many firms have
work, never on the size of their backlogs (FR-006). A single firm is still capped at F (US2
scenario 3).

**Corrected after review (C1)**: the first version broke ties by the oldest due item only. With
more firms than C having work, the older firms kept winning the tie and a new firm waited until
fewer than about C of them were left, so its wait grew with their backlogs. The earlier bound,
"one load duration plus its own ⌈n / F⌉ rounds", held only for one large firm (the SC-002 test).
`FairnessPolicyTest` and `DispatchCycleServiceTest` now cover more firms than C.

**Rejected alternatives**:
- *Arrival order (FIFO across firms).* The ~40,000-engagement firm holds every slot for days.
- *Round robin with a cursor.* Also fair, but a cursor alone ignores loads in flight. The
  least-served tie-break gives the same rotation among firms with nothing in flight; its state is
  in memory only, and losing it at a restart costs at most one more rotation.
- *Weighted fair queuing by firm size.* Unneeded; the spec asks for a bounded wait, not
  proportional shares.

**Relies on**: spec FR-005, FR-006, US2, SC-002 and Clarification 3; the firm-size skew in the
take-home brief (median ~40, largest ~40,000); the planning request (oldest first within a firm).
No reference book or reference scenario from the constitution was consulted for this decision.

## 4. Retry policy (FR-009, FR-010, FR-011)

**Decision**: The HTTP adapter only translates: it returns a neutral `LoadOutcome` (the HTTP
status code it received, `TIMEOUT`, or `CONNECT`) and decides nothing. A 2xx status is a success
(a domain rule in `LoadOutcome`). `RetryPolicy` (pure domain) classifies every other outcome.
Retryable: `TIMEOUT` (no answer before the timeout), an answer meaning overloaded (assumed 429 or
503), any other 5xx, `CONNECT` (connection refused, reset or connect timeout). Permanent: any other
status, which includes not found or bad request (assumed 404, 410, 400). After a
retryable failure, the next attempt is at `now + min(base × 2^(attempt − 1), max)`; when attempts
reach the configured maximum the item is parked. Permanent failures are parked at once.
Defaults: base 30 s, max 30 min, 6 attempts (configuration, agreed with the EMS team). The item
stores only an error code (`TIMEOUT`, `HTTP_503`, `CONNECT`), never a response body (Principle II).
A failed item waits by `next_attempt_at`, so it never blocks other items (FR-011).

**Reason**: exponential backoff spreads retries of a struggling EMS; the cap C already bounds how
many retries can hit EMS at once, so jitter is not needed for safety and is left out to keep tests
deterministic.

**Rejected alternatives**:
- *Fixed delay.* Hammers EMS at the same rate while it is struggling.
- *Jitter.* Not needed under a hard cap; makes assertions on retry times harder.
- *Adaptive slow-down on overload.* Out of scope (spec, Open Question 1).
- *Classification in the HTTP adapter.* Puts a retry rule outside the domain, where the unit tests
  with fakes cannot reach it.
- *Resilience4j Retry or Spring's `@Retryable`.* The retry waits in memory inside the call: it holds
  an EMS slot during the backoff, loses its attempts on a crash, lets a retrying item skip ahead
  of other firms, and cannot live in the plain-Java domain. A circuit breaker from the same
  library is future work, tied to pausing during an EMS outage, which is out of scope.
- *A broker queue with delayed redelivery (Spring Cloud Stream on SQS, RabbitMQ or Kafka).* The
  binder's own retry is in memory too, and a queue cannot give the per-firm cap and fairness
  (FR-005, FR-006), one open item per engagement beyond a short deduplication window (FR-013),
  marking an item done from outside (FR-003) or counts per firm (FR-016). A Spring Cloud Stream
  listener calling `SubmitWorkUseCase` remains the natural inbound adapter (§7), out of scope here.

**Relies on**: spec FR-009 to FR-011, Open Question 1 and Out of Scope; the status codes are our
own assumption, written in contracts/ems-load-api.yaml, because another team owns the API. No reference book or reference scenario from the constitution was consulted for this decision.

## 5. Crash recovery (FR-015, US4)

**Decision**: Every state change is committed before the next step: claimed (lease written)
before the call, outcome written after it. Restart needs no special recovery step: any
`IN_FLIGHT` row left by the dead process is handled by the normal cycle. Its first step expires
leases that have ended (a timed-out attempt: retry or park, same rule as §4); unexpired leases keep
counting against C and F until they end (§1), then the same step expires them. `DONE` rows with
an unexpired lease (marked done during a load of the dead process) also keep counting until the
lease ends, and nothing else needs doing. The scheduler's first tick runs
`ResumeAfterRestartUseCase` before that tick's cycle; it only counts and logs the `IN_FLIGHT`
items found and changes nothing. There is no claim about ordering against
`ApplicationReadyEvent`.

An answer carries the attempt number it was claimed with. The service reloads the item and the
aggregate decides: still `IN_FLIGHT` at that attempt → done, retry or park; marked done during
the load (`DONE`, lease set, same attempt) → only the lease is cleared, releasing the slot; any
other case (lease expired and the item retried, parked or re-claimed) → ignored. The save is
conditional on the version just read; on a conflict the service reloads and applies the answer
again, at most 3 times. After that it logs the conflict (opaque ids) and leaves the item as it is:
its lease expires and step 1 handles it.

**Reason**: done items stay done (no reload), outstanding items are in the table (none lost), and
an item in flight at the crash is retried exactly once, after its timeout (US4 scenarios 1–3,
SC-003).

**Rejected alternatives**:
- *A separate saga or log table.* The work item row already records state, attempt and lease; a
  second record would have to agree with it.
- *Resetting `IN_FLIGHT` items to outstanding at startup immediately.* Breaks Clarification 2:
  EMS may still be running them.
- *A startup recovery step that changes state.* Duplicates step 1 of the cycle; two places would
  have to agree on the same rule.

**Relies on**: spec FR-015, US4, SC-003, Clarifications 1 and 2, and the edge cases. No reference book or reference scenario from the constitution was consulted for this decision.

**Accepted duplicate**: a load that succeeded at EMS whose answer was lost is loaded once more
(spec edge case). The adapter sends `Idempotency-Key: <work item id>` so that EMS could drop it if
it supports that; we do not rely on it.

## 6. How the work list is stored

**Decision**: PostgreSQL, one table `work_item` plus `capacity_settings`, managed by Flyway, and
accessed through Spring Data JDBC in the persistence adapter. `WorkItemRow` is the `@Table`
entity with `@Id` and `@Version`, mapped to and from `WorkItem.Snapshot` by MapStruct. Reads by id,
open item by engagement, oldest due items and expired leases are derived query methods, with
their ordering and `Limit` in the method. Only the three aggregations (`countInFlight`,
`findFirmsWithDueWork`, `status`) are SQL, as `@Query` on the repository interface; no SQL is
built in Java. `capacity_settings` goes through a `CrudRepository`. Key constraints:
- A partial unique index on `engagement_id` where `state IN ('OUTSTANDING','IN_FLIGHT')`: at most
  one open item per engagement (FR-013). Submit saves a new row; a `DuplicateKeyException` from
  that index means an open item exists, and the adapter returns it. So the same engagement from
  three triggers gives one item (SC-004). Done and parked items do not block a new item (US5
  scenario 3, FR-012).
- Every update is conditional on `@Version` (Spring Data JDBC writes `… WHERE id = ? AND
  version = ?` and throws `OptimisticLockingFailureException` when no row matches, which the
  adapter turns into `false`): it guards against two writes racing each other, and is how the
  re-check before a load works (Clarification 1). Answers are not matched by version but by
  attempt number, which the aggregate compares with its current load (§5).
- The version is a `Long`: a null version marks a new row, and Spring Data writes 0 on insert,
  which keeps the contract's first version at 0 (a primitive `long` would start at 1).
- No adapter method opens a transaction around the insert: in PostgreSQL a failed insert aborts the
  surrounding transaction, and the lookup of the existing open item must still run.
- Indexes for the cycle: due outstanding work by `(firm_id, added_at)` and in-flight by
  `lease_until`.
- Terminal rows are kept; purging is out of scope (data-model.md).

Dispatch cycle period: 1 s (configuration). The cycle does two to three queries plus one per firm
receiving slots, at most C, so its cost is independent of the 800,000-row backlog.

**Reason**: the spec needs durable state across restarts, a uniqueness rule, and conditional
updates. A relational table gives all three and is the stack in the constitution and the Part 1
design [D: "PostgreSQL per region"].

**Rejected alternatives**:
- *A message queue (SQS FIFO or similar) as the work list.* No broker in scope. A queue cannot
  answer "fewest in flight per firm" or "is there already an open item for this engagement", and
  visibility timeouts would replace our leases with less control.
- *In-memory list.* Loses its place on a crash.
- *JPA with Hibernate.* A persistence context, dirty checking, lazy loading and its own
  optimistic-locking rules for one flat table with no relations; it would also move the
  conditional update and the duplicate-key handling behind session behaviour that is harder to
  reason about at the moment of a claim. Spring Data JDBC writes exactly the statement that is
  asked for, when it is asked for.
- *Hand-written SQL through `JdbcClient` (the first implementation, T013, replaced after US1).*
  Correct and explicit, but every query was a string built in Java. Spring Data JDBC keeps the
  same behaviour, checked by the same contract test, with derived queries and SQL only where an
  aggregation needs it.

**Relies on**: spec FR-013, FR-015, SC-004; the constitution's stack; `docs/design.md`
("PostgreSQL per region"); the Spring Data JDBC 4.1.1 sources, read on 2026-10-15 for how an
insert sets the version (`JdbcAggregateTemplate`), which exception a version mismatch raises, and
`Limit` support in derived queries. No reference book or reference scenario from the constitution was consulted for this decision.

## 7. Inbound adapters

**Decision**: `adapter/in/scheduler` holds one trigger, `DispatchScheduler`, with a fixed delay.
Its first tick runs `ResumeAfterRestartUseCase` and then `RunDispatchCycleUseCase`; every later tick
runs only `RunDispatchCycleUseCase`. After every 60th cycle it also calls `GetWorkStatusUseCase` and
logs one `status` line: that log line is the channel FR-016 asks for (added after review, I4).
Work enters only through `SubmitWorkUseCase`; there is no endpoint and no broker. In this module
the callers of `SubmitWorkUseCase` and `MarkDoneUseCase` are the tests and the local seed script.

**Event listener as one more adapter**: in production the three triggers (firm activation, publish,
sweep) and the result recorder would reach the worker as events. That is one more inbound adapter,
for example `adapter/in/messaging`, translating a message into `SubmitWorkCommand` or
`MarkDoneUseCase.markDone(...)`. Nothing in `domain` or `application` changes. It is left out
because event delivery is out of scope in the spec.

**Relies on**: the planning request (no endpoint, no broker, event listener as one more adapter);
spec Out of Scope. No reference book or reference scenario from the constitution was consulted for this decision.

## 8. Downstream HTTP adapter

**Decision**: Spring `RestClient` (from `spring-boot-starter-restclient`) on the JDK
`HttpClient`, with a connect timeout (5 s) and a read timeout equal to the load timeout. The lease
is the timeout plus a grace (10 s), so our own call always ends before its lease does. The
adapter translates HTTP results into a neutral `LoadOutcome` (§4), classifies nothing, and never
throws for a downstream answer or a network error. The
endpoint shape is an **assumption** (contracts/ems-load-api.yaml): another team owns it.

**Rejected alternatives**: Spring's HTTP interface clients (more indirection for one call);
WebClient (reactive, §1).

**Relies on**: the Initializr metadata (the `spring-restclient` dependency, 2026-10-14);
contracts/ems-load-api.yaml (our assumption); spec Clarification 2. That `RestClient` can run on
the JDK `HttpClient` with these timeouts is prior knowledge, not checked against library
documentation in this session; `HttpLoadEngagementAdapterIT` confirms it. No reference book or reference scenario from the constitution was consulted for this decision.

## 9. Project generation and test levels

**Decision**: Setup runs the given Initializr command unchanged (checked on 2026-10-14: Spring Boot
4.1.1 is the default and every dependency id resolves). A second task then:
- adds MapStruct 1.6.3 (stable; 1.7 is still beta), `lombok-mapstruct-binding` 0.2.0, ArchUnit
  1.5.1 and `org.wiremock:wiremock-standalone` 3.13.2, with the processors ordered Lombok,
  binding, MapStruct, configuration processor;
- binds `maven-failsafe-plugin` so `*IT` classes run on `mvn verify` and not on `mvn test`;
- renames the generated `FanoutWorkerApplicationTests` to `FanoutWorkerApplicationIT` (D3);
- pins `postgres:latest` to `postgres:18-alpine` in both `compose.yaml` and the generated
  `TestcontainersConfiguration`.

**Why the standalone WireMock artifact**: it shades Jetty. The plain `wiremock` artifact uses
Jetty 11, and Spring Boot's dependency management would align Jetty to its own version and break
it. WireMock 4 supports newer Jetty but is still beta.

**Test levels**: unit tests (domain, application, ArchUnit) run on `mvn test` with fakes of every
outbound port, a settable clock and `ManualExecutor` (queues loads until the test answers them, so
loads in flight are visible): no Spring, no threads, no Docker,
seconds in total. Integration tests (`*IT`) run on `mvn verify` with Testcontainers PostgreSQL and
in-JVM WireMock with millisecond delays and timeouts. No test waits a real minute.

**Relies on**: the live Initializr metadata and the project it generated (2026-10-14); Maven
Central listings for the versions; the planning request (test levels). The WireMock and Jetty
statement is prior knowledge, not checked against WireMock's documentation in this session; the
first WireMock IT confirms it. No reference book or reference scenario from the constitution was consulted for this decision.

## 10. Local run with Docker

**Decision**: `compose.yaml` (generated, PostgreSQL only) gains `wiremock` (`wiremock/wiremock:
3.13.2`, mappings mounted from `wiremock/`) and `worker` (built from the multi-stage
`Dockerfile`). The `worker` service is under the compose profile `app`.

**Reason for the profile**: `spring-boot-docker-compose` starts every service in `compose.yaml`
when the app runs with `./mvnw spring-boot:run`. Without the profile it would also start a second
worker in a container. With it: `spring-boot:run` starts PostgreSQL and WireMock;
`docker compose --profile app up --build` runs all three. Inside the container
`spring.docker.compose.enabled=false`.

**Database password**: `compose.yaml` uses `${POSTGRES_PASSWORD:-fanout}` for PostgreSQL and the
worker. The default `fanout` is a local throwaway value for a database that exists only on the
developer's machine, not a secret (constitution Principle II). Any real environment sets
`POSTGRES_PASSWORD` outside the repository.

**Dockerfile**: stage 1 `eclipse-temurin:21-jdk-alpine` runs `./mvnw -DskipTests package`;
stage 2 `eclipse-temurin:21-jre-alpine` copies the jar and runs it as a non-root user.

**Local seed**: the `local` profile adds `classpath:db/seed-local` to Flyway's locations; the seed
script inserts a few work items for three firms, including an engagement id that the WireMock
mappings fail permanently and one that fails then succeeds. In WireMock the load answers after
5 s locally; in tests, after a few milliseconds.

**Relies on**: the generated `compose.yaml`; Docker Hub tag listings (2026-10-14); the planning
request (compose, profiles, seed). How Spring Boot's Docker Compose support picks services is prior
knowledge, not checked against its documentation in this session. No reference book or reference scenario from the constitution was consulted for this decision.

## Sources

Only what was consulted: the spec, `docs/design.md` [D], the take-home brief, the live Initializr
metadata (start.spring.io, 2026-10-14), the generated project, and Maven Central / Docker Hub
listings for the versions above. Statements about Spring Boot's keep-alive behaviour, WireMock's
Jetty dependency and virtual-thread pinning are from prior knowledge and are to be confirmed in
the Setup phase (the context-load IT and the first WireMock IT will fail if they are wrong).
