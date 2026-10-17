# fanout-worker

A worker that asks the Engagement Management System (EMS) to load engagements after a template change.
Each engagement is a work item in PostgreSQL; a scheduled cycle claims due items and calls EMS.
At most C loads run in total and F per firm, failures are retried or parked, and a crash loses nothing.

## Package layout

```
com.caseware.fanout
├── domain        model, policy (fairness, retry, limits), event, exception
├── application   port/in (use cases), port/out, service, dto
├── adapter       in/scheduler, out/persistence, out/downstream, out/time, out/event
└── config        wiring and properties
```

`ArchitectureTest` enforces the rule: domain uses only `java.*`, application only `java.*` and domain, and neither uses Spring, Lombok, MapStruct or JDBC. Adapters do not depend on services, config or each other; only config wires them together.

## How to run

In WSL, `JAVA_HOME` must point to a Linux JDK 21, for example `export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`.

- `./mvnw test` runs the unit tests against fakes. No Spring, no Docker.
- `./mvnw verify` also runs the integration tests (Testcontainers PostgreSQL, in-JVM WireMock) and needs Docker.
- `docker compose --profile app up --build` starts PostgreSQL, WireMock on `localhost:8089` and the worker. A seed adds 16 items for three firms, including `eng-missing` (404, parked) and `eng-flaky` (503 twice, then 204). From another terminal:
  - `docker compose exec postgres psql -U fanout -d fanout -c "select state, count(*) from work_item group by state"`
  - `docker compose exec postgres psql -U fanout -d fanout -c "update capacity_settings set per_firm_limit = 1"`
  - `docker compose --profile app down` removes the containers.

## Key tradeoffs

**Leases in the database, not in-memory semaphores.** A claim writes `lease_until = now + load timeout + grace`. Each cycle counts unexpired leases per firm. Given up: one count query per cycle, and only one dispatcher may run. Why: after a crash EMS may still be running the old loads. Their leases keep counting; a semaphore would start from zero.

**Durable item-level retry, not Resilience4j, `@Retryable` or broker redelivery.** A timeout, connection failure, 429 or 5xx schedules a later attempt with exponential backoff; the sixth failure parks the item, and other statuses park it at once. Given up: a ready-made library, and jitter. Why: an in-memory retry holds an EMS slot while waiting and is lost on a crash. A queue cannot give a per-firm cap or fairness. C already bounds retries, so jitter is not needed.

**Fairness plus a strict per-firm cap F.** Free slots go one at a time to the firm with the fewest loads in flight, skipping firms at F. Ties go to the firm served least recently (kept in memory, reset on restart), then to the oldest item; within a firm, oldest first. Given up: shares by firm size, priority by reason, and idle capacity when only one firm has work. Why: with N firms having work, each gets a slot at least once every ⌈N / C⌉ load durations, whatever the other firms' backlogs, and no firm takes all of C.

**C and F in a `capacity_settings` table.** The row is created from configuration if missing, and every cycle reads it, so a SQL `UPDATE` applies on the next cycle. Given up: once the row exists, configuration changes are ignored. Why: properties are bound once, and a limit agreed with EMS should survive a restart. The defaults 20 and 5 are placeholders.

**Spring Data JDBC, not JPA.** One flat table, updates conditional on `@Version`, and a partial unique index for one open item per engagement. Given up: JPA's mapping features. Why: JDBC runs exactly the statement asked for, with no persistence context to reason about.

**Blocking calls on virtual threads, not reactive.** Each load is a blocking `RestClient` call on its own virtual thread, holding no database connection. Given up: WebClient and R2DBC. Why: the limit is EMS capacity, not threads, and reactive types would spread into the application layer.

**Test commits before their implementation.** Each task's tests were committed first and shown failing. Given up: a history where every commit builds. 12 of the 50 commits, all between T005 and T029, do not compile, because their tests use classes added later. Why: each failing test is recorded before the code that makes it pass.

## Assumptions about EMS

The load API in `specs/002-fanout-worker/contracts/ems-load-api.yaml` is assumed. The EMS team owns the real API, which is not known; WireMock is a stand-in. The load timeout (5 min) must be longer than the slowest real load, or a load still running counts as failed and EMS runs it twice.

## Left out on purpose

- **One instance per region.** Two instances could count the same free slots and exceed C. Several instances would need claims with `FOR UPDATE SKIP LOCKED` or similar.
- **No circuit breaker.** With backoff of 30, 60, 120, 240 and 480 s, an item that keeps failing fast for about 16 minutes is parked, so a long EMS outage parks every item first tried more than 16 minutes before EMS recovers. Production would pause dispatch while EMS is down.
- **No metrics stack.** Events are log lines. After every 60th cycle the scheduler logs one `status` line with counts per state and per firm, loads in flight and the oldest outstanding age (FR-016). Production would export these as metrics.
- **No inbound API.** Only tests and the local seed create work. Production would add an event-listener adapter for the triggers.
- **No operator requeue.** A parked item stays parked. Production would need a requeue action.

## Known warnings

- Build: Maven prints two `401` metadata warnings for repositories in Flyway's parent POM, and the test JVM prints the Byte Buddy agent notice. Both are harmless.
- Tests and compose: some WARN lines are on purpose (save-conflict give-ups and invalid-configuration startups). The PostgreSQL image prints initdb notices about locales and "trust" authentication.
- Local seed: it is migration `V100`, used only by the `local` profile. On a database that has applied it, a later migration numbered below 100 is out of order and Flyway refuses it until the database is recreated.
