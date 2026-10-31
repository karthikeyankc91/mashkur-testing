# Step 08 — End-to-end wiring, `SUBMISSION.md`, and packaging

## 1. Context & Objective

The last step. Prove the application works as *shipped* rather than as a set of
well-tested parts, write the submission note, and decide what actually goes in
the package.

**Objective:** the full suite is green, `SUBMISSION.md` is complete and short,
and the deliverable contains the right files.

**Traces to:** TRD §5.1 (test levels and the context cache), §5.2 (`@MockitoBean`
once), §5.5 (coverage map), §5.6 (deliberate gaps), §6 (deferred items). PRD
FR-4, AC-1, AC-8a, T-6, G5, NFR-7.

## 2. Dependencies

Everything. This step is the integration point, so it runs last.

## 3. Files

```
src/test/java/com/glofox/memberships/
├── WiringTest.java                     CREATE  (shares contextLoads' context — free)
└── PurchaseApiIntegrationTest.java     CREATE  (T-6 + one 402 end to end)

SUBMISSION.md                           MODIFY  (vendor template → filled)
```

## 4. Step-by-step

### 4.1 Two contexts, not four — and both already exist

TRD §5.1 warned that **each distinct combination of `@MockitoBean` /
`@TestConfiguration` spawns a separate application context** and misses the
cache. Done carelessly, this step adds two or three more contexts and doubles the
suite's runtime for no coverage.

The whole step needs exactly two, and the first one is already paid for:

| Context | Who uses it | What it proves |
|---|---|---|
| **No overrides** | the vendor's `MembershipsApplicationTests.contextLoads()`, plus the new `WiringTest` | the app as *shipped* wires and seeds — real `AlwaysSucceedingPaymentGateway` included |
| **`@MockitoBean PaymentGateway`** | `PurchaseApiIntegrationTest` | both HTTP paths, including `402`, with the gateway's behaviour stubbed per test |

**`WiringTest` is free**, because it declares no overrides and therefore shares
`contextLoads`' cached context. That is the whole reason it is a separate class
rather than assertions bolted onto the integration test — and it is the only
place that can assert anything about the *real* gateway bean, since the other
context replaces it.

```java
@SpringBootTest
class WiringTest {

    @Autowired PaymentGateway gateway;
    @Autowired PlanCatalogue catalogue;
    @Autowired InventoryStore inventory;
    @Autowired Clock clock;

    @Test void defaultGatewayIsTheVendorBean() {
        assertThat(gateway).isInstanceOf(AlwaysSucceedingPaymentGateway.class);
    }

    @Test void catalogueIsSeededBeforeTrafficIsServed() {         // AC-1
        assertThat(catalogue.listInSeedOrder()).hasSize(4);
        assertThat(catalogue.find(LEGACY_PLAN_ID)).get()
            .extracting(Plan::active).isEqualTo(false);
    }

    @Test void onlyThePromoPackHasFiniteInventory() {              // §2.3, structurally
        assertThat(inventory.remaining(LAUNCH_PROMO_PACK_ID)).hasValue(5);
        assertThat(inventory.remaining(MONTHLY_UNLIMITED_ID)).isEmpty();
        assertThat(inventory.remaining(TEN_CLASS_PACK_ID)).isEmpty();
    }

    @Test void clockBeanExists() { assertThat(clock).isNotNull(); }
}
```

`onlyThePromoPackHasFiniteInventory` is worth its four lines: it asserts the
`null`-means-unlimited mapping at the *bean* level, where nothing else does.
Steps 02 and 03 test the store and the wire format; this tests the seeding.

### 4.2 `PurchaseApiIntegrationTest`

```java
@SpringBootTest
@AutoConfigureMockMvc
class PurchaseApiIntegrationTest {

    @MockitoBean PaymentGateway gateway;      // ONE override, behaviour stubbed per test
    @Autowired MockMvc mvc;
}
```

**One `@MockitoBean`, stubbed per test, rather than two classes with two
gateways.** Two classes would mean two contexts; one mock with
`given(...).willReturn(...)` in one test and `willThrow(...)` in another gets both
behaviours from one. This is the single place in the suite where `@MockitoBean`
appears (§5.2) — everywhere else substitution is constructor injection.

| Test | Asserts |
|---|---|
| `purchaseThenFetchRoundTrip` | `POST` → `201` + `Location`; `GET` on that `Location` → `200` with an identical body. **T-6** |
| `plansEndpointServesFourSeededPlans` | `GET /v1/plans` → 4 entries through the real stack |
| `declinedPaymentReturns402AndPersistsNothing` | gateway stubbed to throw `PaymentDeclinedException` → `402 payment_declined`; a subsequent `GET` of a fresh purchase id → `404`; `GET /v1/plans` shows promo still at `5` |
| `unknownPurchaseReturns404` | random UUID → `404 purchase_not_found` |

`declinedPaymentReturns402AndPersistsNothing` is the end-to-end proof of two
separate things at once: that the bean override mechanism works on Boot 4
(`@MockitoBean`, not `@MockBean`), and that AC-8's cleanup holds through the full
stack rather than only at service level.

**Keep this class to four tests.** Everything else is already covered at a
cheaper level — re-asserting the validation matrix here buys nothing and costs
context startup. §5.6's point is that coverage is not the goal; retired risk is.

### 4.3 `SUBMISSION.md` — short, and that is a hard constraint

The vendor template says "Keep it short". After ~2,200 lines of PRD and TRD, the
real risk in this step is transcribing them. **A reviewer reads this in three
minutes.** Budget:

| Section | Budget | Source |
|---|---|---|
| Time spent | 1 line | actual |
| Assumptions | 6–9 bullets | PRD §12 (AS-1…AS-9) — they are already one line each |
| API / design choices | 8–10 bullets | PRD §9 (D-1…D-10), one line each, *with the reason* |
| Tests | ~10 lines | PRD §11's risk ordering + **the mutation results** |
| Known gaps | 5–7 bullets | TRD §6 deferred table + unreached tiers |
| AI tools | 2–3 lines | honest |

**Lead each design bullet with the decision, then the reason, in one sentence.**
`"snake_case throughout, applied as a global Jackson strategy so it cannot be
applied inconsistently"` beats a paragraph. The decisions are already written
this way in PRD §9 — compress, do not re-argue.

**The highest-value content is in two places:**

1. **The mutation results from step 07 §6**, verbatim. *"To check the
   concurrency test could actually fail, I replaced the CAS in `tryReserve` with
   a read-then-write and the oversell test reported 23 successes against 5
   slots."* This is the single most persuasive sentence available, and it costs
   two lines.
2. **The README's own example payload is rejected.** `start_date: "2026-09-01"`
   is in the past, so it returns `400 start_date_in_past` under D-8. Say so
   explicitly — a reviewer who tries the documented example and gets a `400`
   should find it already acknowledged rather than discover a surprise.

**"Known gaps" must lead with the three an experienced reviewer will look for**
(Phase 4 §8), so finding them already written down changes the conversation from
"did you notice?" to "what would you do?":

1. **V-1 — name squatting.** An unauthenticated caller claims the unlimited slot
   for any name; the denial is permanent because release requires cancelling a
   purchase the victim cannot see. Only authentication fixes it.
2. **V-3 — no gateway timeout.** 200 hanging charges exhaust the servlet pool and
   take `GET /v1/plans` down with them. Note that
   `PaymentGatewayUnavailableException` exists for exactly this and currently has
   no producer.
3. **V-4 — charged with no record.** In-memory storage makes the gateway the only
   durable record, so a crash between charging and saving destroys the only local
   evidence the charge happened.

Plus **V-10**, one line: JSON request bodies are not size-capped —
Tomcat's `maxPostSize` covers form encoding, not `application/json`. V-2's
100-character `member_name` bound limits what is *retained* but not what is
*read*. The production answer is a reverse-proxy limit; the in-app sketch is a
`OncePerRequestFilter` rejecting a `Content-Length` over, say, 8 KB.

Also record, briefly:

- **Vendor files touched: two.** `application.yml` (one additive key) and
  `PaymentGateway.java` (three additive nested classes, nothing modified). Say
  that the `charge` signature and its javadoc are byte-identical.
- **`PaymentGateway` was extended, and why** — its own javadoc already read
  *"declined **or** the gateway errors"*, so the distinction was documented
  before it was expressible. One line.
- **DV-1**, the one deviation from the planned design: the seed mapping moved to
  a testable `PlanSeedMapper` because a private method on a `@Configuration`
  class cannot be unit tested.
- **No dependencies added.** The build still has its original three.

**For "AI tools used", the honest and strongest answer is specific.** The README
asks for collaboration you understand and own. Name what the assistance actually
did — e.g. design interrogation and decision logging, with the trade-offs chosen
by you — rather than either hiding it or waving at it. The evidence that it is
owned is that every decision has a recorded reason you can defend, which is what
the follow-up will test.

### 4.4 Packaging — a decision, not a formality

The repository currently contains the exercise **and** ~2,200 lines of planning
documents, plus agent configuration.

**Recommendation: ship the code and `SUBMISSION.md`. Do not ship `docs/`.**

| Include | Exclude |
|---|---|
| `src/`, `build.gradle.kts`, `settings.gradle.kts`, `gradle/`, `gradlew`, `gradlew.bat` | `docs/`, `context/`, `STATE.md` |
| `README.md` (theirs), `SUBMISSION.md` | `AGENTS.md`, `CLAUDE.md`, `.claude/` |
| `.gitignore` | `build/`, `.gradle/` |

The reasoning is worth being able to state: **a 1,700-line TRD attached to a
three-hour exercise reads as disproportionate**, and invites the question of
whether it was written rather than generated — which distracts from code that
can stand on its own. The documents' job was to make you able to answer the
follow-up, and they have done it. Offer them if asked; do not lead with them.

*Alternative, if you want the reasoning visible:* a single-page
`ARCHITECTURE.md` — the §1.4.2 flow diagram, the three-way rule placement from
§1.3, and the rejected alternatives. One page is proportionate where seventeen
are not.

```bash
# Build the zip from a clean tree, so build output and planning docs cannot leak
git archive --format=zip --prefix=glofox-memberships/ -o /tmp/submission.zip HEAD \
  -- src build.gradle.kts settings.gradle.kts gradle gradlew gradlew.bat \
     README.md SUBMISSION.md .gitignore
unzip -l /tmp/submission.zip | grep -E "docs/|STATE|AGENTS|CLAUDE|\.claude" \
  && echo "FAIL: planning artefacts leaked into the submission" \
  || echo "OK: submission contains code and notes only"
```

`git archive` from `HEAD` with an explicit path list is safer than zipping the
working directory — it cannot pick up `build/`, `.gradle/`, editor files, or
anything uncommitted.

## 5. Verification

```bash
cd /Users/karthikeyanchandrarajan/Documents/GitHub/learn

# 1. THE WHOLE SUITE, from clean. This is the number that matters.
./gradlew clean test --console=plain

# 2. Context count — two, not five (TRD §5.1)
./gradlew test --console=plain --info 2>&1 \
  | grep -c "Starting MembershipsApplication\|Root WebApplicationContext"
#   a small number; if it climbs with each new test class, overrides have
#   fragmented the context cache

# 3. The two integration classes
./gradlew test --tests '*WiringTest' --tests '*PurchaseApiIntegrationTest' --console=plain

# 4. The app still boots and serves
./gradlew bootRun --console=plain > /tmp/boot.log 2>&1 &
until grep -q "Started MembershipsApplication" /tmp/boot.log; do sleep 1; done
curl -sS -o /dev/null -w 'plans:%{http_code}\n' localhost:8080/v1/plans
pkill -f MembershipsApplication

# 5. Final vendor-diff audit — exactly two files, both additive
git diff --stat 55036b4 -- src/main/resources/application.yml \
                            src/main/java/com/glofox/memberships/payment/PaymentGateway.java
git diff --stat 55036b4 -- src/main/java/com/glofox/memberships/config/SeedData.java \
                            src/main/java/com/glofox/memberships/payment/AlwaysSucceedingPaymentGateway.java \
                            src/main/java/com/glofox/memberships/MembershipsApplication.java \
                            src/test/java/com/glofox/memberships/MembershipsApplicationTests.java \
                            build.gradle.kts
# expect: the first prints two files, the second prints NOTHING

# 6. SUBMISSION.md is complete and short
grep -c "^## " SUBMISSION.md          # expect 6 — every template section present
test "$(grep -c '^-\{0,1\}' SUBMISSION.md)" -lt 200 && echo "OK: short enough"
grep -n "_$\|^-$\|TODO" SUBMISSION.md && echo "FAIL: unfilled placeholders" || echo "OK: no placeholders"

# 7. Package it
#   (the git archive command from §4.4)
```

**Done when:** `clean test` is green, verification #5's second command prints
nothing, `SUBMISSION.md` has no placeholders, and the zip contains no planning
artefacts.

## 6. Phase 3 is complete after this step

All eight step files written and reviewed. What follows in the overall workflow:

- **Phase 4 — Security & design flaw scrutiny.** Audits this plan for injection,
  state corruption, race conditions and bottlenecks. The material is already
  largely assembled: TRD §4's nine edge cases, §6's W-1/W-2/W-3 windows, and the
  F-1…F-8 / R-1…R-6 logs.
- **Phase 5 — Interview drill.** Consolidates the trade-offs into a defence
  brief, drawing on PRD §9's ten decisions and the rejected-alternatives
  sections.

**A note on what Phase 3 produced that was not expected.** Writing the step files
raised three findings against documents that had already been approved — F-6
(the clock boundary could not satisfy FR-6), F-7 (the payment-failure tests would
pass while a member was permanently barred) and F-8 (nothing tested compensation
under load). F-7 is the one worth remembering: it was invisible in review and
only surfaced when someone had to write down what the test would actually
assert. Specifying implementation is a review technique, not just preparation
for it.

```
==================================================
CURRENT PHASE: Phase 3 [Modular Implementation Plan] -> Step 08 (final)
LAST APPROVED: step_07 (concurrency harness); finding F-8 applied as T-13
NEXT STEP: Review step_08. On approval Phase 3 is complete -> merge
           phase/03-impl to main and begin Phase 4 (security scrutiny).
==================================================
```
