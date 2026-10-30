# Glofox — Memberships Purchase Take-Home

Welcome, and thanks for interviewing with us.

This is a **backend take-home exercise in Java**. Aim for about **2–3 hours** of focused work. A solid core beats an unfinished kitchen sink — stop around **3–4 hours** even if stretch goals are unfinished.

You will discuss your submission in a follow-up technical conversation: design choices, trade-offs, and how you would evolve the system.

## The domain

Glofox is a SaaS platform for boutiques, studios, and gyms. Studio owners sell **memberships** (unlimited monthly plans and class packs) to members.

Your task is to build a small **Memberships Purchase API** for one studio, using **in-memory** storage.

## Timebox and what “done” means

**Required (core):**

1. `GET /v1/plans` — list seeded plans  
2. `POST /v1/memberships/purchases` — purchase a membership  
3. `GET /v1/memberships/purchases/{id}` — fetch a purchase by id  
4. Automated tests for the behaviours you consider highest risk  
5. A short write-up (`SUBMISSION.md`) with assumptions, API choices, known gaps, and approximate time spent  

If those work and the business rules below hold, you are done enough to submit.

## Pre-seeded plans

Plans are defined in [`SeedData.java`](src/main/java/com/glofox/memberships/config/SeedData.java) and logged when the app starts. You do **not** need a create-plan endpoint — seed them into your store at startup.

| Id | Name | Type | Active | Price (cents) | Credits | Remaining slots |
|----|------|------|--------|---------------|---------|-----------------|
| `11111111-1111-1111-1111-111111111111` | Monthly Unlimited | `unlimited` | yes | `8000` | — | — |
| `22222222-2222-2222-2222-222222222222` | 10-Class Pack | `pack` | yes | `5000` | `10` | — |
| `33333333-3333-3333-3333-333333333333` | Launch Promo Pack | `pack` | yes | `2500` | `5` | **`5`** |
| `44444444-4444-4444-4444-444444444444` | Legacy Plan | `unlimited` | **no** | `6000` | — | — |

`remaining_slots` is only set for the Launch Promo Pack. Other plans have unlimited inventory for this exercise.

## Required API

Use JSON. Request/response field names may be `snake_case` or `camelCase` — pick one and be consistent. Amounts must be integers in **cents**.

### `GET /v1/plans`

Return the seeded plans (including inactive ones). Include enough fields for a client to choose a plan (id, name, type, active, price in cents, credits / remaining slots when applicable).

### `POST /v1/memberships/purchases`

```http
POST /v1/memberships/purchases
Content-Type: application/json

{
  "member_name": "Alice",
  "plan_id": "11111111-1111-1111-1111-111111111111",
  "start_date": "2026-09-01"
}
```

Expected behaviour:

1. Validate request shape and semantics (blank name, invalid date, unknown plan, inactive plan, …).
2. Charge via the provided [`PaymentGateway`](src/main/java/com/glofox/memberships/payment/PaymentGateway.java) port (default bean always succeeds; replace or stub it in tests to simulate failure).
3. Create a purchase record (id, plan snapshot or plan id, amount in cents, status, start date, …).
4. Enforce the business rules below.
5. Return **`201 Created`** with a clear purchase payload including the purchase id and amount charged.

### `GET /v1/memberships/purchases/{id}`

Return the purchase, or an appropriate not-found response.

## Business rules (required)

- Monetary amounts are always integers in **cents** — never floats or decimal strings.
- Dates are calendar dates (`YYYY-MM-DD`), not timestamps. Timezones are out of scope for this exercise.
- You cannot purchase an **inactive** plan.
- A member (identified by `member_name` for this exercise) may have **at most one active unlimited** membership at a time.
- The **Launch Promo Pack** has finite `remaining_slots`. A successful purchase must decrement inventory. Concurrent purchases must **never** sell more than available or drive inventory below zero.
- If payment **fails**, no purchase may remain and inventory must not change.

## Deliberately open decisions

These are not hidden requirements — make a coherent choice and be ready to explain it:

- Exact error JSON shape and HTTP status mapping  
- Package / layer layout beyond the hints in this repo  
- Exact purchase response fields  
- How you represent “active unlimited” membership  
- Whether `member_name` uniqueness rules apply beyond the unlimited constraint  

## Payment gateway

[`PaymentGateway`](src/main/java/com/glofox/memberships/payment/PaymentGateway.java) is a port. [`AlwaysSucceedingPaymentGateway`](src/main/java/com/glofox/memberships/payment/AlwaysSucceedingPaymentGateway.java) is the default bean.

In tests, provide an implementation that throws `PaymentGateway.PaymentFailedException` so you can assert that a failed charge leaves no purchase and does not consume promo inventory.

## Storage

Use **in-memory** storage. [`storage/memory`](src/main/java/com/glofox/memberships/storage/memory/) is an empty package waiting for your implementation — you may organise domain, service, and HTTP layers however you prefer.

Design so swapping in a database-backed store later would be plausible.

## What we evaluate

- **Correctness** under the rules above, including payment failure and promo inventory  
- **Where the rules live** — handler vs service vs storage, and why  
- **Concurrency** — especially the last promo slots  
- **Tests** — which ones you chose and why  
- **Trade-offs you can defend** — simple and clear beats clever and opaque  
- **Ownership** — you can walk through and modify your solution in the follow-up  

Stretch features are optional. Core completeness beats volume.

## A note on AI tools

You are welcome to use AI assistants (Cursor, Copilot, ChatGPT, or anything else). Use them the way you would on the job: as a collaborator whose output you understand and own.

In the follow-up we will ask you to walk through your code and the choices behind it. If you would rather not use AI, that is also fine.

## Running the skeleton

Requires **Java 21+** (the project uses a Java 21 toolchain).

```bash
./gradlew bootRun    # starts on :8080; logs seeded plan ids
./gradlew test       # runs tests once you write them
```

No solution tests ship in this repo — write the ones that matter.

## Suggested layout (optional)

```text
src/main/java/com/glofox/memberships/
├── MembershipsApplication.java      # boots app; logs seed plans
├── config/SeedData.java             # seeded plans
├── payment/                         # PaymentGateway port + default success impl
├── storage/memory/                  # empty — your in-memory store
├── ...                              # your HTTP / domain / service packages
```

You may add Spring Web controllers, records, exceptions, etc. Prefer clarity over frameworks for their own sake.

## Stretch (optional)

Only if the core is solid — do not block submission on these:

- `POST /v1/memberships/purchases/{id}/cancel` — soft cancel; restore promo inventory when applicable  
- `Idempotency-Key` header on purchase  
- `GET /v1/memberships/purchases` with `page` / `limit` / `has_more`  
- Structured error body with machine-readable codes  
- Distinguish payment decline vs gateway outage  

## Submitting

1. Complete the core scope and `SUBMISSION.md` (see template below).  
2. Share your solution as instructed by your recruiter (zip, private repo, or branch).  

---

### `SUBMISSION.md` template

Copy to `SUBMISSION.md` and fill in:

```markdown
## Time spent
Approximately: _

## Assumptions
-

## API / design choices
-

## Tests
What I covered and why:

## Known gaps / what I would do next
-

## AI tools used (optional)
-
```

Good luck — we look forward to talking through your solution.
