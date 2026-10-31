# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Operating contract

@AGENTS.md

The imported file above is binding and takes precedence over default behavior. In
particular: no auto-advance between phases, no preambles, and every response must
end with the Current State Anchor block.

## What this repository is

Both the planning workspace **and** the application it plans. The Glofox
take-home skeleton is vendored here, so there is real code to build and run.

| Path | Owner | Notes |
|---|---|---|
| `context/` | human | Input: brief, constraints, verified stack facts |
| `docs/01-prd` … `docs/05-interview-drill` | agent | Phase artifacts, one directory per phase |
| `src/`, `build.gradle.kts`, `gradlew` | vendor + agent | The application |
| `STATE.md` | agent | Durable phase/approval anchor — read first, write last |

Planning is complete and merged. Execution is in progress, driven by
`docs/03-implementation/step_NN_*.md`, so **application code in `src/` is now
expected** — earlier revisions of this file said the opposite, which was true
only before the skeleton was imported.

Three files are vendor-owned and must stay byte-identical:
`config/SeedData.java`, `payment/AlwaysSucceedingPaymentGateway.java`, and
`MembershipsApplication.java`. `payment/PaymentGateway.java` and
`src/main/resources/application.yml` may be changed **additively only**.

## Build and test

```bash
./gradlew test                                  # full suite
./gradlew test --tests '*MemberKeyTest'         # one test class
./gradlew test --rerun-tasks                    # defeat the up-to-date cache
./gradlew bootRun                               # :8080, logs 4 seeded plans
```

Spring Boot 4.0.0 / Spring Framework 7 on a Java 21 target. Three stack facts
that break habits learned on Boot 3: starters are `spring-boot-starter-webmvc`
and `-webmvc-test` (not `-web`/`-test`); `@MockBean` is **removed**, use
`@MockitoBean`; Jackson 3 splits packages, with databind at `tools.jackson.*`
while annotations stay at `com.fasterxml.jackson.annotation.*`. Tests run on
JUnit Jupiter 6. No new dependencies are needed by any step.

## Session start checklist

1. Read `STATE.md` — it names the active phase, the last approved artifact, and the
   next step.
2. Read `context/` — the codebase context, tasks, and constraints.
3. Read only the `docs/` artifacts for the current and immediately prior phase.
4. Resume at the state anchor. Do not re-ask questions already answered in
   committed artifacts.

## Writing artifacts

- Write phase artifacts to the paths in the AGENTS.md layout table — never inline a
  full artifact in chat and leave it unwritten.
- An artifact is written **on approval**, not on drafting. Draft in chat, persist
  once approved.
- After persisting, update `STATE.md` in the same turn, then commit. `STATE.md` must
  always match the anchor block of the latest response.

## Git conventions

One branch per phase, named `phase/0N-<slug>` (e.g. `phase/01-prd`). A phase branch
merges to `main` only when its artifact is approved — so `main` holds settled work
and the branch holds whatever is still being argued about. Never start the next
phase's branch off an unmerged one.

Commit one artifact or one approved section per commit, with the phase as the
subject prefix:

```
phase-2: approve TRD section 3 — API contracts
phase-3: add step_01_db_migration.md
state: advance to phase 2.2
```
