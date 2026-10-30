# Skeleton Tasks

## The brief

Glofox — Memberships Purchase Take-Home. Backend take-home in Java. Build a small
Memberships Purchase API for one studio, using in-memory storage.

> "Aim for about 2–3 hours of focused work. A solid core beats an unfinished
> kitchen sink — stop around 3–4 hours even if stretch goals are unfinished."
>
> "You will discuss your submission in a follow-up technical conversation: design
> choices, trade-offs, and how you would evolve the system."

Domain: Glofox is a SaaS platform for boutiques, studios, and gyms. Studio owners
sell memberships — unlimited monthly plans and class packs — to members.

## Skeleton provided

A booting Spring Boot 4 application with a `PaymentGateway` port and an
always-succeeding default bean, four immutable seeded plans logged at startup,
and an empty `storage/memory` package. No solution tests ship. See
[codebase-context.md](codebase-context.md) for exact contracts.

Suggested (optional) layout hints at `config/`, `payment/`, `storage/memory/`,
plus whatever HTTP/domain/service packages the candidate prefers.

## Required deliverables

- [ ] `GET /v1/plans` — list seeded plans, including inactive
- [ ] `POST /v1/memberships/purchases` — purchase a membership
- [ ] `GET /v1/memberships/purchases/{id}` — fetch a purchase by id
- [ ] Automated tests for the behaviours judged highest-risk
- [ ] `SUBMISSION.md` — assumptions, API choices, known gaps, approximate time
- [ ] The six business rules hold (see PRD FR-5)

## Deliberately open decisions

Not hidden requirements — the brief asks for a coherent choice, explained. All
seven are resolved in PRD §9.

1. Error JSON shape and HTTP status mapping
2. Package / layer layout beyond the repo's hints
3. Exact purchase response fields
4. How "active unlimited" membership is represented
5. Whether `member_name` uniqueness applies beyond the unlimited constraint

## Stretch — optional, must not block submission

- `POST /v1/memberships/purchases/{id}/cancel` — soft cancel; restore promo
  inventory where applicable
- `Idempotency-Key` header on purchase
- `GET /v1/memberships/purchases` with `page` / `limit` / `has_more`
- Structured error body with machine-readable codes
- Distinguish payment decline from gateway outage

Tiered with gates in PRD §10.

## Explicit non-goals

Timezones (stated out of scope). A create-plan endpoint (plans are seeded).
Durable persistence (in-memory by assignment). Volume of features over core
correctness — stated twice in the README.

## How it is evaluated

Quoted from the README, because these are the actual grading axes:

1. Correctness under the rules, including payment failure and promo inventory
2. **Where the rules live** — handler vs service vs storage, and why
3. **Concurrency** — especially the last promo slots
4. **Tests** — which ones were chosen and why
5. **Trade-offs you can defend** — simple and clear beats clever and opaque
6. **Ownership** — able to walk through and modify the solution in the follow-up

Note that four of six are about reasoning, not output. AI assistance is
explicitly permitted, "the way you would on the job: as a collaborator whose
output you understand and own."
