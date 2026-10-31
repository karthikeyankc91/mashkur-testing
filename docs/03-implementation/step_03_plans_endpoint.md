# Step 03 — `GET /v1/plans` end to end

## 1. Context & Objective

The first runnable milestone. After this step the application serves a real
endpoint and `curl` returns the four seeded plans. It needs nothing beyond steps
01–02: no service, no payment, no clock, no compensation — which is exactly why
it was placed here rather than with the other web work (`00_INDEX.md`).

This is also where the global `snake_case` decision (D-1 / TRD §3.1) is made and,
critically, **proven** — it was chosen for being impossible to apply
inconsistently, at the cost of being invisible at the DTO.

**Objective:** `curl localhost:8080/v1/plans` returns four plans in seed order
with `snake_case` keys, the inactive plan included, and `null` distinguishable
from `0`.

**Traces to:** TRD §3.1 (DTOs and naming), §3.2 (`GET /plans`), §1.1 (no
`PlanService`), §2.2 (composition cost). PRD FR-2, AC-2, D-1, T-8.

## 2. Dependencies

| Needs | From |
|---|---|
| `Plan`, `PlanType` | step 01 |
| `PlanCatalogue`, `InventoryStore` ports | step 01 |
| Seeded store beans (`SeedingConfig`) | step 02 |

**Blocks:** nothing. This branch of the graph terminates — step 05 proceeds from
step 02 independently.

## 3. Files

```
src/main/resources/application.yml              MODIFY  ← vendor file, additive only
src/main/java/com/glofox/memberships/web/
├── dto/PlanResponse.java                       CREATE
├── PlanAssembler.java                          CREATE
└── PlanController.java                         CREATE
src/test/java/com/glofox/memberships/web/
└── PlanControllerTest.java                     CREATE  (T-8)
```

## 4. Step-by-step

### 4.1 `application.yml` — the one global naming decision

**Add `jackson:` under the existing `spring:` key. Do not introduce a second
`spring:` block** — the file already has `spring.application.name`, and a
duplicate top-level key silently overwrites in YAML.

```yaml
server:
  port: 8080

spring:
  application:
    name: memberships-task
  jackson:
    property-naming-strategy: SNAKE_CASE     # D-1, applied globally

logging:
  level:
    root: INFO
    com.glofox.memberships: INFO
```

Two cautions:

- **Do not set `spring.jackson.default-property-inclusion: non_null`.** PRD AC-2
  requires `credits` and `remaining_slots` to be *present* with a `null` value —
  a client must distinguish "unlimited" from "field missing". Jackson's default
  includes nulls, so the correct action is to leave it alone. This is a
  configuration that breaks the contract by being added.
- **This is the second of only two vendor-file changes** in the exercise (the
  other is step 04). It is additive — one key, nothing modified — but it belongs
  in the `SUBMISSION.md` list of touched vendor files.

**Boot 4 + Jackson 3 caveat:** `spring.jackson.property-naming-strategy` is
confirmed present in Boot 4's configuration metadata, and Boot maps the string to
the strategy internally, so the YAML value is unaffected by Jackson 3's package
relocation. Confirmed by *running* verification #3, not by assumption.

### 4.2 `dto/PlanResponse.java`

```java
package com.glofox.memberships.web.dto;

/** Wire names are snake_case via spring.jackson.property-naming-strategy. */
public record PlanResponse(UUID id, String name, String type, boolean active,
                           int priceCents, Integer credits, Integer remainingSlots) { }
```

- **`type` is a `String`, not `PlanType`.** The lowercase wire vocabulary
  (`"unlimited"`) is produced by the assembler, so no Jackson concern and no
  `@JsonValue` ever touches the domain enum (TRD §3.1).
- **`credits` and `remainingSlots` are boxed `Integer`** precisely so they can be
  `null`. `priceCents` is a primitive `int` because it is never absent.
- The javadoc comment is the agreed mitigation for §3.1's acknowledged cost: a
  reader of this record cannot otherwise tell the wire says `price_cents`.

### 4.3 `PlanAssembler.java`

A pure function of its inputs, so it is a static utility with no Spring
annotation and no injection:

```java
public final class PlanAssembler {

    private PlanAssembler() { }

    public static PlanResponse toResponse(Plan plan, OptionalInt remaining) {
        Integer remainingSlots = remaining.isEmpty() ? null : remaining.getAsInt();
        return new PlanResponse(
            plan.id(),
            plan.name(),
            plan.type().name().toLowerCase(Locale.ROOT),   // UNLIMITED -> "unlimited"
            plan.active(),
            plan.priceCents(),
            plan.credits(),
            remainingSlots);
    }
}
```

**Static, not `@Component`.** It has no state and no dependencies, so a bean
would add wiring ceremony and force an `@Import` into every slice test. Pure
functions do not need a container. (A small refinement on TRD §1.1, which listed
the assembler without specifying bean-ness.)

**Write the `Integer` in an assignment, not a ternary expression inside the
constructor call.** `cond ? remaining.getAsInt() : null` happens to compile here,
but the `int`/`null` ternary is the shape that produces surprise unboxing `NPE`s
when edited later. The named local makes the boxing explicit and deliberate.

**This line is the single place in the system where "unlimited inventory"
becomes a JSON `null`** (TRD §3.2). Everywhere inside the boundary it is an
absent map entry; here, at the edge, it becomes a wire value. If that mapping
ever needs to change, this is the only place to look.

### 4.4 `PlanController.java`

```java
@RestController
@RequestMapping("/v1/plans")
public final class PlanController {

    private final PlanCatalogue catalogue;
    private final InventoryStore inventory;

    PlanController(PlanCatalogue catalogue, InventoryStore inventory) {
        this.catalogue = catalogue;
        this.inventory = inventory;
    }

    @GetMapping
    public List<PlanResponse> list() {
        return catalogue.listInSeedOrder().stream()
            .map(plan -> PlanAssembler.toResponse(plan, inventory.remaining(plan.id())))
            .toList();
    }
}
```

- **No `PlanService`, and the controller holds two ports directly.** There is no
  rule to enforce here, and NFR-6 puts rules in the service — a service with no
  content would be a layer added for symmetry (TRD §1.1). If a rule ever appears,
  introducing one is a two-minute change.
- **Returning `List<PlanResponse>` yields a bare JSON array**, per TRD §3.2's
  choice of no envelope.
- **This is §2.2's acknowledged cost, visible in one line:** splitting catalogue
  from inventory means the endpoint composes two sources. That line is the entire
  price paid for an immutable, lock-free catalogue.

### 4.5 `PlanControllerTest.java` — T-8

```java
@WebMvcTest(PlanController.class)
@Import(SeedingConfig.class)        // real seeded stores, not mocks
class PlanControllerTest { ... }
```

**Real stores via `@Import`, not `@MockitoBean`.** `@WebMvcTest` does not scan
`@Configuration` classes, so the store beans must be supplied. Importing the real
`SeedingConfig` means this test exercises the actual seed mapping and the actual
`OptionalInt → null` composition. Mocking `PlanCatalogue` would reduce T-8 to a
test of the assembler, and the seed data *is* what AC-2 is about.

| Test | Asserts | Defends |
|---|---|---|
| `returnsFourPlansInSeedOrder` | `$.length() == 4`; `$[0].id` is `1111…`, `$[3].id` is `4444…` | FR-2 stable order |
| `includesInactivePlan` | `$[3].active == false` | AC-2 — easy to filter out by reflex |
| `typeIsLowercase` | `$[0].type == "unlimited"` | §3.1 enum vocabulary |
| `usesSnakeCase` | body contains `price_cents` and `remaining_slots`, and **does not contain** `priceCents` | **D-1, and the mitigation for invisible config** |
| `nullsArePresentNotOmitted` | `$[0]` *has* a `credits` key whose value is null — assert the key exists, not just that the value is null | AC-2: unlimited ≠ absent |
| `promoPackHasFiniteSlots` | `$[2].remaining_slots == 5` | AC-2, and `0` vs `null` distinguishability |

**`usesSnakeCase` is doing real work, not box-ticking.** TRD §3.1 chose global
configuration knowing it makes the wire contract invisible at the DTO; the
accepted mitigation was that a test asserts the wire names. The negative
assertion matters more than the positive one — if the property name were
misspelled or the key nested wrongly, Boot would start silently and serve
`priceCents`.

## 5. Verification

```bash
cd /Users/karthikeyanchandrarajan/Documents/GitHub/learn

# 1. Full suite
./gradlew test --console=plain

# 2. T-8 specifically
./gradlew test --tests '*PlanControllerTest' --console=plain

# 3. THE RUNNABLE MILESTONE — prove the endpoint and the naming strategy for real
./gradlew bootRun --console=plain > /tmp/boot.log 2>&1 &
until grep -q "Started MembershipsApplication" /tmp/boot.log; do sleep 1; done

curl -s localhost:8080/v1/plans | python3 -m json.tool | head -40

#   assertions a human should eyeball, and a script can check:
curl -s localhost:8080/v1/plans | python3 - <<'PY'
import json,sys
plans=json.load(sys.stdin)
assert len(plans)==4, f"expected 4 plans, got {len(plans)}"
assert "price_cents" in plans[0], "snake_case NOT applied — check spring.jackson key nesting"
assert "priceCents" not in plans[0], "camelCase leaked"
assert "credits" in plans[0] and plans[0]["credits"] is None, "null must be present, not omitted"
assert plans[0]["remaining_slots"] is None, "unlimited inventory must serialise as null"
assert plans[2]["remaining_slots"]==5, "promo pack must show 5"
assert plans[3]["active"] is False, "inactive plan must be included"
assert plans[0]["type"]=="unlimited", "enum must be lowercase on the wire"
print("OK: all AC-2 assertions hold")
PY

pkill -f MembershipsApplication

# 4. Import boundary still holds — web may import store, but domain must stay clean
grep -rn "org\.springframework\|jackson" \
     src/main/java/com/glofox/memberships/domain \
     src/main/java/com/glofox/memberships/store \
  && echo "FAIL" || echo "OK: domain and store still framework-free"

# 5. No Jackson annotation reached the domain (TRD §3.1's actual claim)
grep -rn "@Json" src/main/java/com/glofox/memberships/domain && echo "FAIL" || echo "OK"

# 6. Vendor diff is exactly one additive YAML key
git diff HEAD -- src/main/resources/application.yml
# expect: +  jackson:  /  +    property-naming-strategy: SNAKE_CASE   and nothing removed
```

**Done when:** all six pass, the `curl` script prints `OK`, and `git status`
shows 4 new files and 1 modified (`application.yml`).

## 6. Notes for the next step

Step 04 is the only change to vendor *code* — two nested exception subtypes on
`PaymentGateway` (TRD §1.7). It is ~10 lines and deliberately its own step so the
vendor diff is one reviewable commit.

Nothing in step 03 is reused by the purchase flow except the `snake_case`
configuration and the DTO conventions. `PurchaseResponse` (step 06) follows the
same rules: boxed types only where `null` is meaningful, enum vocabulary produced
in the assembler, no Jackson annotation on anything in `domain/`.

```
==================================================
CURRENT PHASE: Phase 3 [Modular Implementation Plan] -> Step 03
LAST APPROVED: step_02 (in-memory adapters, seeding, clock)
NEXT STEP: Review step_03. On approval -> step_04_payment_port_extension.md
==================================================
```
