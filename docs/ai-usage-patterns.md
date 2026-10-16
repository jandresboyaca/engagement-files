---
title: AI Usage
description: How AI assistants were used in this exercise, as patterns
---

# AI usage in this exercise

| Pattern | How it was applied |
|---|---|
| Spec-Driven Development (SDD) | specify → plan → tasks flow, with a project constitution. The spec states what and why; the result comes from a later step, after my approval. |
| Human-in-the-loop (human gate) | Nothing is applied without my explicit approval; ideas first, changes after. |
| Flipped interaction | For the architecture, the assistant interviewed me one question at a time instead of delivering a full design. |
| Least privilege | Read-only access to the real repository; changes go through a branch, a commit and a PR that I approve. |
| Grounding and traceability | Every claim about the existing system is labeled as stated, inferred or assumed, with a confidence level and its source. |
| Maker-checker | One agent executes and another audits (`CLAUDE.md` executor, `AGENTS.md` auditor). |
| Isolated-context reviewer | A subagent without the session's context rereads the source, and its report is reviewed too. |
| Verification before closing | "Validated" is not taken at face value: validators, screenshots and a review of every note. |
| Architecture Decision Records (ADR) | Decisions and rejected alternatives are recorded when they happen (D1–D19, A1–A7). |
| Generate-then-select (spike) | Ask for options, try one in a separate file and compare before overwriting. |
| Context engineering | Persistent memory and project instructions: response format, tools, progress. |
| Parallel agent fan-out | Only for independent tasks, at the end. |
