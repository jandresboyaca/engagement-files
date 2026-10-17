# Implementation Plan: Template-Publish Fan-out Worker

**Branch**: `002-fanout-worker` | **Date**: 2026-10-14 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/002-fanout-worker/spec.md`

## Summary

A background worker that loads engagements from a durable work list by calling the Engagement
Management System (EMS), within a global cap C and a per-firm cap F, fairly across firms, with
retries, parking, and safe restart. It is one Spring Boot module, `fanout-worker/`, built as ports
and adapters: a plain-Java domain (work item aggregate, concurrency, fairness and retry policies),
plain-Java application services behind five inbound ports, and adapters for a scheduler,
PostgreSQL, the EMS HTTP call, the clock and event logging. Loads are blocking calls on virtual
threads. What is in flight is counted from leases in the work list, not from in-memory
semaphores, so the cap holds across a crash (research §1, disagreement D1).

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Spring Boot 4.1.1 (Initializr: `spring-restclient`, `jdbc`, `postgresql`,
`flyway`, `lombok`, `configuration-processor`, `validation`, `testcontainers`, `docker-compose`;
after US1 the `jdbc` starter was replaced by `spring-boot-starter-data-jdbc`, research §6);
added by hand: MapStruct 1.6.3 with `lombok-mapstruct-binding` 0.2.0, ArchUnit 1.5.1,
`wiremock-standalone` 3.13.2 (research §9)

**Storage**: PostgreSQL 18 (`postgres:18-alpine`), schema by Flyway; tables `work_item`,
`capacity_settings` (data-model.md); accessed through Spring Data JDBC (no JPA)

**Testing**: JUnit 5, AssertJ, ArchUnit (`mvn test`, no Spring, no Docker); Testcontainers and
WireMock for `*IT` (`mvn verify`, failsafe)

**Target Platform**: Linux container (multi-stage Dockerfile, Java 21 JRE, non-root); local run
with docker compose

**Project Type**: Background worker (no web server, no endpoint)

**Performance Goals**: sustain C concurrent one-minute loads, with C in the tens [D: ~19 for a
30-day backfill, 50 for a publish]; a dispatch cycle (period 1 s) costs a few queries. Corrected
after review (I3): one of them, `findFirmsWithDueWork`, reads every outstanding row, because
`next_attempt_at` is not in `ix_work_item_due`, so its cost grows with the backlog (research §6)

**Constraints**: never more than C loads in flight in total or F per firm, including across a
restart; no test waits a real minute; logs carry opaque ids only

**Scale/Scope**: backlog up to the engagements of one region (≤ ~800,000 across all regions [P]);
up to ~4,000 firms; one worker instance per region

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Applies how | Status |
|---|---|---|
| I. Test-First | Every task pair is test then code; tests must fail first. Unit tests use fakes of the five outbound ports and run in seconds; each adapter and the restart and capacity scenarios have `*IT` integration tests. The critical flows of this component are dispatch, retry, restart and deduplication, all covered at both levels | Pass |
| II. Confidentiality | The worker never reads engagement content: it sends two opaque ids and reads only a status code. Logs, events and `last_error` hold opaque ids and error codes only, never response bodies. DB credentials come from the environment; compose uses `${POSTGRES_PASSWORD:-fanout}`, whose default is a local throwaway value, not a secret (research §10). TLS to EMS is a deployment setting of the HTTP client's base URL (`https` in real environments; plain HTTP only to local WireMock). Access control: there is no inbound interface to protect | Pass |
| III. Auditability | Not applicable as written: the worker does not create, update, delete or read an engagement file. The load is an EMS action and is audited by EMS. The worker's own trail is the work item row (state, attempts, last error, timestamps), kept after completion, plus one log line per completion or park. Work items are operational state, not audit evidence, so they are not append-only | Pass (N/A, justified) |
| IV. Data Integrity | Not engagement files. In the same spirit: no update overwrites silently (every save is conditional on `version`), and nothing is deleted; terminal rows are kept (purge out of scope) | Pass |
| V. Simplicity | One module, no broker, no Spring Cloud, no reactive stack. The detailed hexagonal layout has more packages than this component strictly needs; see Complexity Tracking | Pass with justification |
| VI. Clear Contracts | No HTTP API is exposed, so no OpenAPI of our own. The ports are documented contracts (contracts/inbound-ports.md, outbound-ports.md); errors are explicit (`LoadOutcome`, `InvalidTransitionException`, validation in value objects). The outbound EMS call is documented in OpenAPI and marked as an assumption (contracts/ems-load-api.yaml) | Pass |
| Technology constraints | Java and Spring Boot on the constitution's stack; Spring Cloud is not used because no part of this component needs it. Decisions record their sources and trade-offs (research.md) without invented citations | Pass |

**Post-design re-check (after Phase 1)**: unchanged. The design added one port and one table
(`CapacitySettingsPort`, `capacity_settings`) for FR-007; both are justified in research §2 and do
not affect any principle.

## Project Structure

### Documentation (this feature)

```text
specs/002-fanout-worker/
├── plan.md              # This file
├── research.md          # Decisions, reasons, rejected alternatives, disagreements D1-D3
├── data-model.md        # Work item, state transitions, tables
├── quickstart.md        # Unit tests, integration tests, docker compose
├── contracts/
│   ├── inbound-ports.md
│   ├── outbound-ports.md
│   └── ems-load-api.yaml  # ASSUMPTION: owned by the EMS team
└── tasks.md             # /speckit-tasks (not created here)
```

### Source Code (repository root)

```text
fanout-worker/
├── pom.xml                      # Initializr + MapStruct, ArchUnit, WireMock, failsafe
├── mvnw, mvnw.cmd, .mvn/
├── Dockerfile                   # multi-stage: temurin 21 JDK + ./mvnw package → temurin 21 JRE, non-root
├── compose.yaml                 # postgres:18-alpine, wiremock/wiremock:3.13.2, worker (profile "app")
├── wiremock/
│   └── mappings/                # load-engagement: 5 s delay; one id → 404; one id → 503 twice then 200
├── src/main/java/com/caseware/fanout/
│   ├── FanoutWorkerApplication.java
│   ├── domain/
│   │   ├── model/               # WorkItem (+ Snapshot), WorkItemId, FirmId, EngagementId, Attempt,
│   │   │                        # WorkItemState, Reason, LoadOutcome
│   │   ├── policy/              # ConcurrencyLimits, FairnessPolicy, RetryPolicy, InFlightCounts, FirmBacklog
│   │   ├── event/               # DomainEvent, WorkItemCompleted, WorkItemParked
│   │   └── exception/           # InvalidTransitionException
│   ├── application/
│   │   ├── port/in/             # SubmitWorkUseCase, MarkDoneUseCase, RunDispatchCycleUseCase,
│   │   │                        # ResumeAfterRestartUseCase, GetWorkStatusUseCase
│   │   ├── port/out/            # LoadEngagementPort, WorkItemRepositoryPort, CapacitySettingsPort,
│   │   │                        # ClockPort, DomainEventPublisherPort
│   │   ├── service/             # SubmitWorkService, MarkDoneService, DispatchCycleService,
│   │   │                        # ResumeAfterRestartService, WorkStatusService
│   │   └── dto/                 # SubmitWorkCommand, SubmitResult, DispatchResult, WorkStatus
│   ├── adapter/
│   │   ├── in/scheduler/        # DispatchScheduler (@Scheduled fixed delay; first tick runs resume, then the cycle)
│   │   └── out/
│   │       ├── persistence/     # JdbcWorkItemRepository, JdbcCapacitySettings, WorkItemRow (Spring Data JDBC entity),
│   │       │                    # WorkItemRowRepository, CapacitySettingsRow(+Repository), WorkItemRowMapper (MapStruct)
│   │       ├── downstream/      # HttpLoadEngagementAdapter (RestClient, connect/read timeouts)
│   │       ├── time/            # SystemClockAdapter
│   │       └── event/           # LoggingDomainEventPublisher
│   └── config/                  # FanoutProperties (@ConfigurationProperties, @Validated), wiring of
│                                # services, policies, virtual-thread Executor, RestClient
├── src/main/resources/
│   ├── application.properties           # virtual threads, keep-alive, defaults
│   ├── application-local.properties     # compose profile, seed location
│   ├── db/migration/                    # V1__work_item.sql, V2__capacity_settings.sql
│   └── db/seed-local/                   # V100__seed_work_items.sql
└── src/test/java/com/caseware/fanout/
    ├── architecture/ArchitectureTest.java           # unit (mvn test)
    ├── domain/model/, domain/policy/                # unit
    ├── application/service/                         # unit, with application/fake/ (fakes of every out port)
    ├── application/port/out/WorkItemRepositoryContract.java  # abstract; run by FakeWorkItemRepositoryTest and JdbcWorkItemRepositoryIT
    ├── adapter/in/scheduler/DispatchSchedulerTest.java  # unit, no Spring
    ├── adapter/out/persistence/*IT.java             # Testcontainers PostgreSQL
    ├── adapter/out/downstream/*IT.java              # WireMock
    ├── CapacityScenarioIT.java, RetryParkScenarioIT.java,
    │   RestartScenarioIT.java, DeduplicationScenarioIT.java
    ├── FanoutWorkerApplicationIT.java               # renamed from the generated test (D3)
    ├── TestcontainersConfiguration.java             # generated; image pinned
    └── TestFanoutWorkerApplication.java             # generated
src/test/resources/application-test.properties       # ms timeouts and backoff, fast cycle
```

**Structure Decision**: one Maven module at `fanout-worker/`, base package
`com.caseware.fanout`, layered as requested.

**Dependency rules** (enforced by `ArchitectureTest`):
- `domain` depends on nothing outside `java.*`.
- `application` depends only on `domain` and `java.*`.
- `adapter.in.*` and `adapter.out.*` depend on `application.port`, `application.dto` and
  `domain`, never on another adapter or on `application.service`.
- Only `config` (and the generated application class) may depend on `application.service` and on
  adapters, to wire them.
- `domain` and `application` contain no `org.springframework`, `lombok`, `org.mapstruct`,
  `java.sql` or `javax.sql`. Lombok and MapStruct appear only in `adapter` and `config`.

**Configuration** (`fanout.*`, validated): `limits.global` and `limits.per-firm` (initial values
of `capacity_settings`), `load.timeout` (default 5 min, agreed with EMS; Clarification 2),
`load.lease-grace` (10 s), `load.connect-timeout` (5 s), `retry.max-attempts` (6),
`retry.base-delay` (30 s), `retry.max-delay` (30 min), `dispatch.period` (1 s), `ems.base-url`.
Also `spring.threads.virtual.enabled=true` and `spring.main.keep-alive=true` (research §1).

**Compose details**: the generated PostgreSQL service is pinned to `postgres:18-alpine`, its
database and user set to `fanout`, and its password to `${POSTGRES_PASSWORD:-fanout}` (the worker
uses the same variable). `wiremock` mounts
`./wiremock`. `worker` builds from the Dockerfile, is under the compose profile `app`, depends on
both, and sets `SPRING_PROFILES_ACTIVE=local` and `SPRING_DOCKER_COMPOSE_ENABLED=false`
(research §10).

**Setup phase, first two tasks** (from the planning request):
1. At the repository root, run exactly the given `curl … start.spring.io … && unzip … && rm …`
   command. Change nothing else in that task.
2. Edit `pom.xml` (MapStruct, binding, ArchUnit, WireMock, failsafe, processor order), delete
   `HELP.md`, rename the generated test to `FanoutWorkerApplicationIT`, pin `postgres:latest` in
   `compose.yaml` and `TestcontainersConfiguration` (research §9, D3).

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| Detailed hexagonal layout (four domain sub-packages, five in-ports, five out-ports, DTO package) for one small worker (Principle V) | Requested; Part 2 asks to "focus on interfaces, contracts and correctness". The ports let every rule be tested with fakes in seconds and keep the EMS API (an assumption) behind one adapter | A flat package with a service, a repository and a client would be shorter, but the EMS contract and persistence would leak into the rules, and the layer rules could not be enforced by a test |
| Fifth outbound port and a settings table (D2) | FR-007: change C and F while running with no endpoint and no Spring Cloud | JMX: hard to reach in a container and lost on restart. File watch: custom code |
| Lease-based in-flight counting instead of the requested semaphores (D1) | Clarification 2 across a crash | Semaphores reset on restart and would let up to 2 × C loads reach EMS |
