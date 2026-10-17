# Quickstart: Template-Publish Fan-out Worker

How to validate the worker once it is implemented. Contracts: [contracts/](contracts/). Data:
[data-model.md](data-model.md).

## Prerequisites

- JDK 21 (only for running Maven outside Docker; the wrapper downloads Maven).
- Docker running (integration tests and the local stack).

All commands run from `fanout-worker/`.

## 1. Unit tests (no Docker, seconds)

```bash
./mvnw test
```

Runs domain, application and architecture tests with in-memory fakes, a settable clock and
`ManualExecutor`, which queues loads until the test answers them. Expected: all green, no
container started, no Spring context.

| Spec item | Unit test |
|---|---|
| State transitions, FR-002, FR-003 | `domain/model/WorkItemTest` |
| Marked done during its load keeps its slot until the load answers or the lease expires, FR-003 with FR-004, FR-005 | `domain/model/WorkItemTest`, `DispatchCycleServiceTest` (C = 1: mark the in-flight item done; no new load starts until the fake EMS answers or the lease expires) |
| C and F, FR-004, FR-005, SC-001 | `domain/policy/ConcurrencyLimitsTest`, `application/service/DispatchCycleServiceTest` |
| Fairness, FR-006, US2, SC-002 | `domain/policy/FairnessPolicyTest`, `DispatchCycleServiceTest` |
| Retry and park, FR-009 to FR-011, US3 | `domain/policy/RetryPolicyTest`, `DispatchCycleServiceTest` |
| Restart, FR-015, US4 | `application/service/DispatchCycleServiceTest` (restart cases) |
| Startup report of items left in flight | `application/service/ResumeAfterRestartServiceTest`, `adapter/in/scheduler/DispatchSchedulerTest` |
| Repository contract on the fake | `application/fake/FakeWorkItemRepositoryTest` (runs `WorkItemRepositoryContract`) |
| One load per engagement, FR-013, US5, SC-004 | `application/service/SubmitWorkServiceTest` |
| Limits changed while running, FR-007, SC-006 | `DispatchCycleServiceTest` |
| Status counts, FR-016 | `application/service/WorkStatusServiceTest` |
| Layer rules | `architecture/ArchitectureTest` |

## 2. Integration tests (Docker, under a few minutes)

```bash
./mvnw verify
```

Runs the unit tests, then every `*IT` with Testcontainers PostgreSQL (`postgres:18-alpine`) and
in-JVM WireMock. Stub delays and timeouts are milliseconds.

| Scenario | Integration test | Expected |
|---|---|---|
| Repository contract, uniqueness, conditional save | `adapter/out/persistence/JdbcWorkItemRepositoryIT` | runs `WorkItemRepositoryContract` against PostgreSQL: same behaviour as the fake |
| HTTP results to `LoadOutcome`, timeouts | `adapter/out/downstream/HttpLoadEngagementAdapterIT` | neutral outcomes, no classification (table in outbound-ports.md) |
| Retry then park | `RetryParkScenarioIT` | `eng-flaky` done after 503, 503, 204; `eng-missing` parked with `HTTP_404`; always-503 parked after the maximum attempts |
| Restart with loads in flight, SC-003 | `RestartScenarioIT` | across 10 stop-and-start cycles: no item lost; no done item reloaded; in-flight items loaded at most once more, after their lease |
| One engagement submitted three times, SC-004 | `DeduplicationScenarioIT` | one row, one WireMock request |
| Cap under load, SC-001 | `CapacityScenarioIT` | WireMock never sees more than C concurrent requests |
| Marked done during a load, FR-003 with FR-004 | `CapacityScenarioIT` | with C = 1, marking the in-flight item done starts no second load until WireMock answers |
| Context starts | `FanoutWorkerApplicationIT` | context loads with the `test` profile; invalid limits or a missing timeout stop startup |

## 3. Local stack with docker compose

Everything in containers:

```bash
docker compose --profile app up --build
```

Starts PostgreSQL, WireMock (mappings from `wiremock/mappings/`, 5 s delay) and the worker with
the `local` profile. Flyway applies the schema and the local seed. Expected in the worker log,
opaque ids only:
- loads start, never more than the configured C in total or F per firm;
- `WorkItemCompleted` for most seeded items after about 5 s each;
- the engagement mapped to 404 is parked at once (`WorkItemParked`, `HTTP_404`);
- the engagement mapped to 503 twice is retried and then completed.

Or run the worker from the IDE or Maven, with Spring Boot starting PostgreSQL and WireMock:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Change the limits while it runs (FR-007), then watch the next cycle use them:

```bash
docker compose exec postgres psql -U fanout -d fanout \
  -c "update capacity_settings set global_limit = 1, per_firm_limit = 1, updated_at = now();"
```

Inspect the work list:

```bash
docker compose exec postgres psql -U fanout -d fanout -c "select state, count(*) from work_item group by state;"
```

Stop and remove:

```bash
docker compose --profile app down -v
```
