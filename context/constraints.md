# Project Constraints

## Interview format

| | |
|---|---|
| Format | Take-home, followed by a live technical discussion |
| Time budget | 2–3h focused; hard stop ~3–4h even with stretch goals unfinished |
| AI assistance | Explicitly permitted — "as a collaborator whose output you understand and own" |
| Follow-up | Walk through the code and the choices behind it; may be asked to modify it live |
| Submission | Zip, private repo, or branch, per recruiter instruction, plus `SUBMISSION.md` |

The follow-up is the binding constraint. Anything that cannot be explained aloud
is a liability regardless of correctness — which rules out clever concurrency
tricks and any code written but not understood.

## Hard technical constraints

- **Java 21+**, Spring Boot 4.0.0, Gradle wrapper as shipped.
- **In-memory storage only.** `storage/memory` is the suggested home.
- **Integer cents** for all monetary amounts. Never float, never decimal string.
- **Calendar dates** (`YYYY-MM-DD`) for `start_date`. Timezones out of scope.
- **Charge through the `PaymentGateway` port.** Do not bypass it or edit the
  default bean to fail.
- **`SeedData` is not to be edited** — seed it into the store at startup.
- **No create-plan endpoint.**
- **Design so a database-backed store could be swapped in later.**
- Only three dependencies are on the classpath; adding one is a decision to
  justify, not a reflex (see `codebase-context.md` constraint 6).

## Soft preferences — negotiable, argue if warranted

- "Prefer clarity over frameworks for their own sake."
- "Simple and clear beats clever and opaque."
- `snake_case` or `camelCase` both acceptable, but be consistent (PRD D-1 picks
  `snake_case`).
- Package layout is the candidate's call beyond the repo's hints.
- Purchase response fields are the candidate's call.

## Trade-offs to be ready to defend

Expected lines of questioning in the follow-up. Each maps to a PRD decision.

1. **Where do the business rules live, and why there?** → NFR-6
2. **How is the last promo slot protected, and what does your mechanism cost?**
   → NFR-1, and the TRD's chosen mechanism
3. **Payment and inventory both mutate with no transaction — which happens first,
   and what breaks if the process dies between them?** → NFR-2
4. **You guarded the promo counter. Did you guard the one-unlimited rule?**
   → NFR-3
5. **Why these tests and not others?** → PRD §11, risk-ordered
6. **What does "active unlimited" mean, and what did that simplification cost?**
   → D-2, AS-1
7. **Why RFC 9457 rather than a custom envelope?** → D-3
8. **How would this change with two instances behind a load balancer?** → AS-4
9. **Why `402` for a decline?** → D-6, Q-3
10. **Which AI output did you keep, and why do you trust it?** — the README
    raises this; the honest answer is the decision log in PRD §9

## Anti-goal

A broader feature surface at the cost of core correctness. The README states the
bar twice. A sixth promo pack sold is a failed submission no matter what else
works.
