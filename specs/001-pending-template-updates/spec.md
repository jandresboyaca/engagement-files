# Feature Specification: Pending Template Updates for Engagement Files

**Feature Branch**: `001-pending-template-updates`

**Created**: 2026-10-08

**Status**: Draft

**Input**: User description: "Produce the requirements documentation for the take-home exercise in
`Staff_Java_Developer_-_Take-Home_Test 2.md` (Part 1 problem: pending template updates for
engagement files). Source of truth: that file only. Do not invent numbers or requirements; where
the source is silent, mark it as an open question."

**Source**: `docs/Staff_Java_Developer_-_Take-Home_Test 2.md`. Each item cites it in brackets.
Source sections: Context (C), The Problem (P), Systems You Can Build On (S), Part 1 (Part 1),
Part 2 (Part 2), Assumptions & Constraints (A), Time Expectation (T). Figures wrapped in «» in the
source are recorded as given. This document says WHAT and WHY only.

## Clarifications

### Session 2026-10-08

Decisions taken by the user in this conversation (earlier session) and carried into this spec:

- Q: What does "declined" pin? → A: Only the declined version. Each later version is offered on
  its own, and its summary includes changes carried over from any declined version.
- Q: What happens when a version is withdrawn after publication? → A: A pending withdrawn
  version stops being shown as pending. If already applied or declined, the record is kept and
  the user is told it was withdrawn.
- Q: Do per-firm summaries fall under in-region data rules? → A: Yes. Summaries stored for a firm
  are that firm's data and stay in its required region.
- Q: Which cloud stack is used for this requirement? → A: AWS.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - See which engagement files have pending updates (Priority: P1)

A practitioner sees at a glance which engagement files have a product template update waiting for
a decision. [P: "Users need to see at a glance which of their engagement files have pending
updates"]

**Why this priority**: Without it users cannot know a decision is owed. It is the core of the
feature and a viable MVP on its own.

**Independent Test**: Publish an update to a template used by some of a firm's files; confirm
exactly those files show as pending and the others do not.

**Acceptance Scenarios**:

1. **Given** a firm with files created from a template, **When** an update to that template is
   published, **Then** each of those files shows a pending update within seconds. [P: "within
   seconds of a template being published"]
2. **Given** a file created before this feature existed, **When** an update to its template is
   published, **Then** it shows as pending like a new file. [S: "needs to work for existing
   engagements, not only newly created ones"]
3. **Given** a file whose update was applied or declined, **When** the list is viewed, **Then** it
   no longer shows that update as pending.

---

### User Story 2 - Understand the pending changes before deciding (Priority: P1)

For a file with a pending update, the user reads a plain-language summary of the inbound changes
and applies or declines the update. [P: "users need a summary of the pending inbound changes in
order to make that apply/ decline decision"; S: "users are non-technical and would strongly
prefer something human-readable"]

**Why this priority**: The summary is what makes the decision possible for non-technical users.

**Independent Test**: For an update with known differences, a non-technical reader can state what
changes from the summary alone, without raw diff data.

**Acceptance Scenarios**:

1. **Given** a file with a pending update, **When** the user opens it, **Then** they see a
   human-readable summary of the changes between their current version and the update.
2. **Given** a summary shown on a past date, **When** reviewed months later, **Then** the exact
   summary shown can be reproduced and explained. [Part 1: "a summary shown to a user in March
   may need to be defended during a regulatory review in November"]

---

### User Story 3 - Decide with accumulated updates, branches and withdrawals (Priority: P2)

The user deals with updates that pile up before they decide, market branches of a template,
withdrawn versions, and a later version that contains the changes of one they declined. [P:
"Template versions do not form a simple line. Markets branch, versions are occasionally withdrawn
after publication, and a firm that declined v5 may later be offered v7, which contains v5's
changes."]

**Why this priority**: These cases make the indicator and summaries trustworthy in real use; the
single-update case delivers value first.

**Independent Test**: Publish two updates before the user decides, withdraw one, and confirm what
the user sees and is offered next.

**Acceptance Scenarios**:

1. **Given** two updates published before the user decides, **When** the user reviews the file,
   **Then** the pending state and summaries account for both. [P: "several updates accumulating
   before the user makes the accept/decline choice"]
2. **Given** a firm that declined v5, **When** v7 (which contains v5's changes) is published,
   **Then** v7 is offered on its own and its summary includes the changes carried over from v5.
3. **Given** a published version later withdrawn, **When** the user views the file, **Then** a
   still-pending withdrawn version stops showing as pending, and an already-decided one stays on
   record with a notice that it was withdrawn.

---

### Edge Cases

- Several updates accumulate on one file before any decision. [P]
- Template versions branch by market, so updates are not a single sequence. [P]
- A version is withdrawn after publication, before or after a decision. [P]
- A declined version's changes reappear inside a later version. [P]
- One firm has far more files than the median (largest about 40,000, median about 40). [P]
- Event notifications from the template or engagement systems are lost, delayed or duplicated.
  [Part 1: "unreliable event delivery"]
- Existing files must be covered with no maintenance window. [S: "There is no maintenance window"]
- Firms with in-region data requirements («EU», «Canada»). [S]

## Requirements *(mandatory)*

### Objectives

- **O-1**: Users can see at a glance which engagement files have pending updates. [P: "Users need
  to see at a glance which of their engagement files have pending updates"]
- **O-2**: Users get a summary of the pending inbound changes so they can apply or decline. [P:
  "a summary of the pending inbound changes in order to make that apply/ decline decision"]
- **O-3**: The information is up to date. [P: "provide users with that information in an
  up-to-date manner"]
- **O-4**: Summaries are readable by non-technical users. [S: "users are non-technical and would
  strongly prefer something human-readable"]
- **O-5**: It works on the live system, including existing engagement files. [S: "the
  pending-update indicator needs to work for existing engagements, not only newly created ones"]

### Technical Constraints

- **TC-1**: Reading a file's template information requires loading the engagement, about 1 minute
  per file. This is a hard constraint. [S: "~1 minute per file; this should be treated as a hard
  constraint"]
- **TC-2**: A file's stored form is not directly queryable for its current state. [S: "not a
  directly queryable record of its current state"]
- **TC-3**: Files store only the template ID and version used at creation. [S: "Engagement files
  currently store the template ID and version used for their creation"]
- **TC-4**: The system that loads engagements also handles creation and the accept/decline
  decision. [S: "The same system which loads engagements is also responsible for handling
  engagement creation, and for processing the user's accept/decline decision"]
- **TC-5**: The template database is shared across firms and holds no engagement data; files live
  in customer-specific databases. [S: "shared between customer firms"; "does not currently retain
  any information about Engagement files, which are stored in their own customer-specific
  databases"]
- **TC-6**: Any template version is quickly retrievable by ID and version. Publishing writes to
  that store. [S: "individual templates can be quickly retrieved given a template ID and a
  version"]
- **TC-7**: A quick, reliable JSON diff between two versions may be assumed. [S: "You can assume
  that you have access to a quick and reliable way to compare two different versions"]
- **TC-8**: Hooks or events may be added to actions in the template and engagement systems. [S:
  "You may assume you can add hooks or events"]
- **TC-9**: All systems are live with no maintenance window. [S: "All of these systems are already
  live. There is no maintenance window"]
- **TC-10**: Templates are zip archives of structured JSON data. [S]
- **TC-11**: The stack for this requirement is AWS. [A: "You may choose any cloud provider or
  stack (note we are an AWS shop)"; Clarifications]

### Business Constraints

- **BC-1**: All-in run cost, including inference spend, stays under «$X» per month; X is not given
  (see Open Questions). [P: "must stay under «$X»/month"]
- **BC-2**: Some firms must keep data in-region («EU», «Canada»). [S: "contractually or legally
  required to keep their data in-region"]
- **BC-3**: Engagement content is client-confidential audit workpaper material. [S]
- **BC-4**: Product template content is not firm-specific. [S: "Product template content is not
  firm-specific"]
- **BC-5**: A summary shown in March may need defending in a November regulatory review. [Part 1]
- **BC-6**: AI augments practitioners and does not replace professional judgment. [C: "AI at
  Caseware is not intended to replace professional judgment"]
- **BC-7**: The apply or decline decision belongs to the customer. [P: "the customer will either
  apply the update to an engagement or decline the update"]

### Functional Requirements

- **FR-001**: Users MUST be able to see which of their engagement files have a pending template
  update. [P: "which of their engagement files have pending updates"]
- **FR-002**: Users MUST be shown, for each pending update, a summary of the inbound changes. [P]
- **FR-003**: Summaries MUST be human-readable, not raw diffs. [S: "human-readable"]
- **FR-004**: A newly published update MUST appear as pending within seconds of publication. [P:
  "within seconds of a template being published"]
- **FR-005**: Users MUST be able to apply or decline each pending update per engagement file. [P]
- **FR-006**: After a decision, the file's pending state MUST reflect it. [P: "that update needs
  to be evaluated by our customers"; inferred, see Assumptions]
- **FR-007**: The system MUST handle several updates accumulating before a decision. [P]
- **FR-008**: A decline MUST pin only the declined version. Each later version MUST be offered
  separately, and its summary MUST include the changes carried over from any declined version.
  [P: "what 'declined' actually pins"; Clarifications]
- **FR-009**: A withdrawn version still pending MUST stop being shown as pending. A withdrawn
  version already applied or declined MUST stay on record and the user MUST be told it was
  withdrawn. [P: "versions are occasionally withdrawn after publication"; Clarifications]
- **FR-010**: The system MUST handle market branches and a later version that contains the
  changes of a previously declined one. [P: "Markets branch"; "a firm that declined v5 may later
  be offered v7, which contains v5's changes"]
- **FR-011**: The feature MUST work for existing engagement files. [S]
- **FR-012**: New engagement files MUST keep being created from the most recent template version.
  [P: "initially created with the most recent version of a product template"]
- **FR-013**: Each summary shown MUST be reproducible for later regulatory defence. [Part 1]
- **FR-014**: Summaries stored for a firm MUST be treated as that firm's data and stay in its
  required region. [S: "in-region"; Clarifications]

### Non-Functional Requirements

| ID | Requirement | Measure from source | Source |
|----|-------------|---------------------|--------|
| NFR-1 | Freshness: pending updates appear shortly after publication. | "within seconds"; no number given | P: "within seconds of a template being published" |
| NFR-2 | Scale: firms. | about 4,000 | P: "roughly «4,000» firms" |
| NFR-3 | Scale: active engagement files. | 800,000 | P: "«800,000» active engagement files" |
| NFR-4 | Scale: products. | 40 | P: "across «40» products" |
| NFR-5 | Skew: files per firm. | median about 40, largest about 40,000 | P: "the median firm has «~40», the largest «~40,000»" |
| NFR-6 | Update rate. | about 1 per week per product | P: "once per week per product" |
| NFR-7 | Cost, all-in including inference. | under «$X» per month; X unknown | P |
| NFR-8 | Load capacity. | about 1 minute per file, hard constraint | S |
| NFR-9 | No maintenance window for rollout, migration or backfill. | none allowed | S: "There is no maintenance window" |
| NFR-10 | Data residency: in-region firms' data stays in region. | «EU», «Canada» | S |
| NFR-11 | Confidentiality of engagement content. | none | S: "client-confidential" |
| NFR-12 | Defensibility of summaries over time. | example March to November | Part 1 |
| NFR-13 | Tolerates unreliable event delivery. | none | Part 1: "unreliable event delivery" |
| NFR-14 | Observability and SLOs defined. | none given | Part 1: "observability and SLOs" |
| NFR-15 | Recovery from failures. | none | Part 1: "failure recovery" |
| NFR-16 | Migration and backfill of existing files. | none | Part 1: "migration and backfill" |

### Key Entities *(include if feature involves data)*

- **Firm**: A customer organization that owns engagement files. Some require in-region data.
- **Product Template**: A published template for one product, with versions that branch by market
  and can be withdrawn.
- **Template Version**: One published version of a product template.
- **Engagement File**: A firm's year of work for a client, created from a template version, with
  that template ID and version recorded.
- **Pending Update**: A template version offered to a file that awaits apply or decline.
- **Update Summary**: The human-readable description of a pending update's changes, kept for later
  defence.
- **Decision**: A user's apply or decline of a pending update, and what it pins.

### Out of Scope

- **OOS-1**: Applying the updated template content. [P: "Actually applying the updated template
  content is outside the scope of this assignment"]
- **OOS-2**: A full application build. [Part 2: "This is not a full application build"]
- **OOS-3**: Over-optimizing the exercise. [T: "Please do not over-optimize"]
- **OOS-4**: The design document, the Part 2 fan-out worker and the diagrams; they are separate
  deliverables. [Deliverables]

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A published update shows as pending on affected files within seconds (numeric bound
  not given; see Open Questions).
- **SC-002**: The feature serves about 4,000 firms, 800,000 active files and 40 products, with the
  largest firm at about 40,000 files and the median about 40.
- **SC-003**: The feature copes with about one template update per week per product.
- **SC-004**: Total monthly run cost, including inference spend, stays under «$X» (value not
  given; see Open Questions).
- **SC-005**: 100% of existing engagement files get a pending status, with no maintenance window.
- **SC-006**: Template information is never read through the slow engagement load (about 1 minute
  per file) as the way of keeping the indicator current.
- **SC-007**: Any summary shown on a past date can be reproduced exactly months later.

## Assumptions

- The take-home document is the only source; no figures or requirements are added beyond it.
- A fast, reliable JSON diff exists and hooks or events can be added to both existing systems.
  [S]
- FR-006 (pending state clears after a decision) is an inference from the apply-or-decline flow.
  [A: "You may state reasonable assumptions"]

### Open Questions (source is silent or ambiguous)

- **OQ-1**: What is the monthly cost ceiling «$X»? (BC-1, NFR-7)
- **OQ-2**: What does "within seconds" mean as a number and percentile, and does it bound the
  indicator, the summary, or both? (FR-004, NFR-1)
- **OQ-3**: How should several accumulated updates be presented: individually, combined, or
  both? (FR-007)
- **OQ-4**: What counts as an "active" engagement file? (NFR-3)
- **OQ-5**: What retention period and evidence make a summary defensible in a regulatory review?
  (FR-013, NFR-12)
- **OQ-6**: Which role in a firm may apply or decline? (FR-005)
- **OQ-7**: Are the «»-wrapped figures final or illustrative? (NFR-2 to NFR-6)
- **OQ-8**: What are the target SLOs and the allowed backfill lag for existing files? (NFR-14,
  NFR-16)
- **OQ-9**: Does an AI-generated summary count as inference spend, and is AI required or allowed
  for summaries? (BC-1, BC-6)
- **OQ-10**: Resolved. The stack is AWS (Clarifications), and the project constitution was amended
  to v1.1.0 to name AWS as the deployment platform. (TC-11)
