# engagement-files Constitution

engagement-files manages audit engagement files: documents, workpapers, and evidence tied to
client engagements.

## Core Principles

### I. Test-First (NON-NEGOTIABLE)
Tests MUST be written before the implementation they cover, and MUST fail before the
implementation makes them pass (Red-Green-Refactor). Every feature MUST have unit tests, and
every critical flow (upload, versioning, deletion/retention, access control, audit logging)
MUST have integration tests. A change MUST NOT merge if its tests were added after the fact
without a documented reason.
Rationale: audit evidence is only trustworthy if the system handling it is demonstrably correct.

### II. Confidentiality and Security by Default
Client data is sensitive. Access MUST be enforced through role-based access control, denying by
default. Data MUST be encrypted in transit and at rest. Logs, traces, metrics, and error
messages MUST NOT contain sensitive content (file contents, client identifiers beyond opaque
IDs, credentials, tokens). Secrets MUST NOT be committed to the repository.
Rationale: a confidentiality breach harms clients and the firm's professional obligations.

### III. Auditability and Traceability
Every create, update, delete, and access of an engagement file MUST be recorded in an
immutable audit log capturing who, what, and when. Audit records MUST be append-only and MUST
NOT be modifiable or deletable through application interfaces. An operation MUST NOT be
reported successful if its audit record could not be written.
Rationale: the audit trail is itself evidence of the integrity of the engagement.

### IV. Data Integrity
Files MUST be versioned; an existing version MUST NOT be silently overwritten. Deletions MUST
be soft deletes governed by explicit retention rules; permanent purging MUST occur only via
the retention process and MUST itself be audited.
Rationale: engagement files are subject to regulatory retention and must remain reconstructable.

### V. Simplicity
The simplest solution that satisfies current requirements MUST be chosen (YAGNI). New
dependencies, services, and abstractions MUST be justified by a present need, not an
anticipated one. Starting as a well-structured modular monolith is the default; splitting into
services requires a documented decision per the architecture rules below.
Rationale: unnecessary complexity increases defects, cost, and attack surface.

### VI. Clear Contracts
All APIs MUST be documented (e.g., OpenAPI) and validated at the boundary. Errors MUST be
explicit and use one consistent format across the system. Breaking contract changes MUST be
versioned and communicated before release.
Rationale: consistent, validated contracts keep clients and services decoupled and safe.

## Technology & Architecture Constraints

- Current stack: Java, Spring Boot, Spring Cloud, deployed on Amazon Web Services (AWS).
  This reflects the current decision and may evolve; any change MUST go through the amendment
  process in Governance. (Changed from GCP in v1.1.0: the take-home source states the company
  is an AWS shop and the stack for this work is AWS.)
- Architectural decisions MUST be recorded and MUST state which reference or scenario they rely
  on and the trade-offs considered. Citations MUST NOT be fabricated; cite only what was
  actually consulted.
- Reference books: designing-data-intensive-applications (data models, replication,
  partitioning, stream vs batch); building-microservices (decomposition, communication
  patterns, Conway's Law); domain-driven-design (bounded contexts, aggregates, context
  mapping); fundamentals-software-architecture (architecture styles, quality attributes,
  selection matrix); software-architecture-hard-parts (trade-off analysis, granularity, saga
  patterns); clean-architecture (dependency rule, layers, ports and adapters);
  enterprise-integration-patterns (messaging, routing, transformation, outbox pattern);
  site-reliability-engineering (SLIs/SLOs/SLAs, error budgets, observability);
  software-architecture-in-practice (quality attribute scenarios, architectural tactics, NFR
  definition).
- Reference scenarios: monolith-to-microservices (strangler fig); event-driven-architecture
  (async communication, pub/sub); multi-tenant-saas (tenant isolation, shared infrastructure);
  zero-downtime-migration (blue-green, canary, expand-contract); api-gateway-pattern (API
  routing, BFF, gateway selection).

## Development Workflow & Quality Gates

- Work MUST happen in small, focused branches.
- Code review is REQUIRED before merge; reviewers MUST verify compliance with this
  constitution.
- CI MUST pass (lint and tests) before merging; failing CI MUST NOT be bypassed.
- Complexity or deviations from a principle MUST be justified in the pull request.

## Governance

This constitution overrides all other practices and guidance. Amendments MUST include a
documented rationale and a version bump, and are made via a reviewed pull request. Versioning
follows semantic versioning: MAJOR for backward-incompatible removals or redefinitions of
principles or governance; MINOR for new principles/sections or materially expanded guidance;
PATCH for clarifications and wording fixes. Compliance MUST be checked in every code review,
and architectural decisions MUST be reviewed against the Technology & Architecture
Constraints section.

**Version**: 1.1.0 | **Ratified**: 2026-10-08 | **Last Amended**: 2026-10-08
