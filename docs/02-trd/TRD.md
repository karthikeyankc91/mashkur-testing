# TRD — Glofox Memberships Purchase API

> **STATUS: SKELETON (Phase 2.1) — awaiting review before any section is expanded.**
> Every section below is an empty heading plus the question it must answer. No
> answers yet. Sections are expanded **one at a time** in Phase 2.2, each pausing
> for approval.

**Upstream:** [`docs/01-prd/PRD.md`](../01-prd/PRD.md) (approved, `f54450b`) ·
[`context/codebase-context.md`](../../context/codebase-context.md) (verified skeleton)

**Scope of this document:** how the PRD's requirements are built. The PRD fixed
*what* and *why*; this fixes *how*. Implementation step files are Phase 3.

---

## 1. Architecture

### 1.1 Layering and package layout
Resolves README open decision #2. What packages exist, what each owns, and what
each is forbidden from importing.

### 1.2 Dependency direction and the port boundary
How NFR-4 (swappable storage) is enforced rather than merely intended — which
direction dependencies point, and what keeps Spring and web types out of the
domain.

### 1.3 Where the business rules live
NFR-6 and evaluation criterion #2. Why rules sit in the service and not the
controller or the store, and what the handler is left holding.

### 1.4 Concurrency model and the atomicity boundary ⭐
**The load-bearing section.** Resolves NFR-1, NFR-2, NFR-3 together:
- What unit of work must be atomic, and expressed in whose vocabulary
- The ordering of charge vs inventory decrement, and what breaks in each window
- The mechanism (lock / CAS / reserve-then-commit) and its cost
- How the service states *what* must be atomic without dictating *how*, so a
  DB-backed store can substitute a row lock or unique constraint
- Why the same mechanism covers the one-unlimited race, not just the counter

### 1.5 Validation strategy
Hand-roll, or add `spring-boot-starter-validation` to a deliberately three-dependency
build? Weighed against "clarity over frameworks for their own sake."

### 1.6 Startup seeding and bean wiring
How FR-1's "before traffic is served" is guaranteed, and where the
`PlanSeed → domain` mapping lives.

---

## 2. Data Models

### 2.1 Domain types
The records and enums, their fields, and which are immutable.

### 2.2 Catalogue versus inventory
`SeedData` is immutable, so mutable `remaining_slots` cannot live on the plan
record. Where the split falls and what each half owns.

### 2.3 Inventory representation
What type holds remaining slots, and how `null = unlimited` is encoded so it
cannot be mistaken for zero (PRD T-4). Candidate: make the absence explicit in
the type rather than carrying a nullable integer.

### 2.4 Member identity key
How D-4's trimmed, case-folded key is derived and stored alongside the
as-submitted display name — and where that derivation lives so it cannot be
bypassed.

### 2.5 Store port contracts
The port method signatures. **Shaped by §1.4** — the atomic operations the
service needs are what these methods must be.

### 2.6 Seed mapping
`PlanSeed(String type)` → domain enum, and what happens on an unrecognised value.

---

## 3. API Contracts

### 3.1 DTOs and the `snake_case` mapping
Request/response types, and how D-1 is applied — per-field annotation versus a
global naming strategy.

### 3.2 `GET /v1/plans`
Exact response payload, field by field, with the null cases from PRD AC-2.

### 3.3 `POST /v1/memberships/purchases`
Exact request and `201` response, mapped to PRD FR-6.

### 3.4 `GET /v1/memberships/purchases/{id}`
Response and the `400`-vs-`404` boundary for a malformed id.

### 3.5 Problem Details shape
D-3 concretely: the `ProblemDetail` fields used, how the `code` extension is
attached, and the `type` URI convention.

### 3.6 Exception-to-status mapping
How PRD AC-10's table is implemented — which exceptions exist, where they are
translated, and how an unchecked `PaymentFailedException` is prevented from
escaping as a `500`.

---

## 4. Edge Cases

### 4.1 `remaining_slots: null` versus `0`
The one-character bug (PRD T-4), and the type-level guard against it.

### 4.2 Unchecked exception escape paths
Every path by which `PaymentFailedException` could bypass handling.

### 4.3 Partial-failure windows
Enumerated against the §1.4 ordering: what is observable if the process dies at
each step, and which windows are accepted versus closed.

### 4.4 Concurrent same-member unlimited
NFR-3 concretely: what two racing threads each see, and where they serialise.

### 4.5 Validation ordering
Why PRD FR-3's eight steps run in that order, and what a client sees when more
than one would fail.

### 4.6 Cancel and inventory restore (Tier 2)
Restore semantics, double-cancel, and whether cancelling frees the one-unlimited
slot (PRD Q-2).

### 4.7 Retry and double-charge (Tier 3)
Why the port's missing idempotency parameter pushes dedupe into the service, and
how that second race interacts with §1.4.

---

## 5. Test Strategy

### 5.1 Test levels
Unit versus `@WebMvcTest` slice versus full `@SpringBootTest`, and which of PRD
T-1…T-8 lands at which level — chosen for speed and precision, not symmetry.

### 5.2 Gateway substitution
The Boot 4 mechanism (`@MockitoBean` or `@TestConfiguration` + `@Primary`) and
why `@MockBean` is unavailable.

### 5.3 Concurrency harness
Barrier design, thread count, and the assertions that make NFR-1 non-flaky —
including asserting the *final* inventory value, not just the success count.

### 5.4 Coverage map
PRD T-1…T-8 → test classes and method names.

### 5.5 Deliberately untested
What is left uncovered inside the timebox, and why that is the right trade — this
answers evaluation criterion #4 directly.

---

## 6. Risks and Deferred Items

Carried from PRD §13 and `STATE.md`, plus anything surfaced while expanding the
sections above.

---

## Proposed expansion order

**Not document order.** §1.4 determines the store port contracts (§2.5), which
determine the service shape (§1.3), which determine the error paths (§3.6).
Expanding top-to-bottom would mean rewriting §1.1–1.3 once §1.4 settles.

| Order | Section | Why here |
|---|---|---|
| 1 | **§1.4** Concurrency and atomicity | Everything downstream depends on it. Also the hardest interview question |
| 2 | **§2** Data models | Falls out of §1.4 once the atomic unit is known |
| 3 | **§1.1–1.3, §1.5–1.6** Remaining architecture | Layout follows the types, not the reverse |
| 4 | **§3** API contracts | Mechanical once the domain exists |
| 5 | **§4** Edge cases | An audit of 1–4; finds gaps while they are still cheap |
| 6 | **§5** Test strategy | Needs the final shape to map tests onto |
| 7 | **§6** Risks | Accumulated throughout; closed last |

```
==================================================
CURRENT PHASE: Phase 2 [Technical Requirement Document] -> Sub-Phase 2.1
LAST APPROVED: docs/01-prd/PRD.md (merged to main, f54450b)
NEXT STEP: Review this skeleton and the proposed expansion order. On approval
           -> Phase 2.2, expanding one section at a time starting with §1.4.
==================================================
```
