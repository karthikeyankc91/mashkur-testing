# Step 06 — Purchase endpoints and the error contract

## 1. Context & Objective

Expose `POST /v1/memberships/purchases` and `GET /v1/memberships/purchases/{id}`,
and implement the one exception-to-status table that owns every error response in
the application.

After this step the full core API works end to end and **every row of PRD AC-10
is observable over HTTP** — which is what makes Tier 1 (machine-readable error
codes) complete rather than aspirational.

**Objective:** both endpoints behave to contract, the `errors` array batches
shape failures while semantic failures arrive with their own codes (F-2), and no
internal detail leaks on a `500`.

**Traces to:** TRD §3.1 (DTOs), §3.3 (`POST`), §3.4 (`GET` and the 400/404
boundary), §3.5 (Problem Details shape), §3.6 (the mapping table), §1.5
(validation split), §1.7 (the base-type fallback row). PRD FR-3, FR-4, FR-6,
FR-7, AC-3, AC-4, AC-9, AC-10, AC-11, T-6, T-7.

## 2. Dependencies

| Needs | From |
|---|---|
| `PurchaseService`, `getById`, the seven exceptions | step 05 |
| `PaymentDeclinedException`, `PaymentGatewayUnavailableException` | step 04 |
| Global `snake_case` already configured | step 03 |

**Blocks:** step 08's end-to-end test. Step 07 drives the service directly and
does not need this step.

## 3. Files

```
src/main/java/com/glofox/memberships/web/
├── dto/PurchaseRequest.java            CREATE
├── dto/PurchaseResponse.java           CREATE
├── dto/PlanSnapshotResponse.java       CREATE
├── dto/FieldErrorResponse.java         CREATE
├── PurchaseRequestValidator.java       CREATE
├── RequestValidationException.java     CREATE  (web-layer, carries field errors)
├── PurchaseAssembler.java              CREATE  (static, like PlanAssembler)
├── PurchaseController.java             CREATE
└── ApiExceptionHandler.java            CREATE  ← the single mapping table

src/test/java/com/glofox/memberships/web/
└── PurchaseControllerTest.java         CREATE  (T-6, T-7, all of AC-10)
```

## 4. Step-by-step

### 4.1 Request and response DTOs

```java
/** Wire names are snake_case via spring.jackson.property-naming-strategy. */
public record PurchaseRequest(String memberName, UUID planId, LocalDate startDate) { }

public record PurchaseResponse(UUID id, String memberName, PlanSnapshotResponse plan,
                               int amountCents, String status, LocalDate startDate,
                               String transactionId, Instant createdAt) { }

public record PlanSnapshotResponse(UUID planId, String name, String type,
                                   int priceCents, Integer credits) { }

public record FieldErrorResponse(String field, String message) { }
```

- **`plan` is a nested object** (TRD §3.3), making D-7's "this is a historical
  snapshot" visible in the shape rather than buried in a flat sprawl.
- **`amountCents` sits outside the snapshot** even though it equals
  `plan.priceCents` today — different facts, and FR-6 asks for the amount charged
  as first-class.
- **`status` and `type` are `String`**, lowercased by the assembler, so no
  Jackson concern reaches the domain enums.
- **`FieldErrorResponse.field` carries the *wire* name** (`member_name`), not the
  Java name. See §4.2's wart.

### 4.2 `PurchaseRequestValidator` — shape only, all at once

```java
final class PurchaseRequestValidator {
    private PurchaseRequestValidator() { }

    static final int MAX_MEMBER_NAME = 100;      // V-2

    static List<FieldErrorResponse> validate(PurchaseRequest request) {
        List<FieldErrorResponse> errors = new ArrayList<>();
        if (request.memberName() == null) {
            errors.add(new FieldErrorResponse("member_name", "must be provided"));
        } else if (request.memberName().strip().length() > MAX_MEMBER_NAME) {
            errors.add(new FieldErrorResponse("member_name",
                       "must be at most " + MAX_MEMBER_NAME + " characters"));
        }
        if (request.planId() == null)     errors.add(new FieldErrorResponse("plan_id", "must be provided"));
        if (request.startDate() == null)  errors.add(new FieldErrorResponse("start_date", "must be provided"));
        return errors;
    }
}
```

**The length bound is V-2, and it belongs here rather than in `MemberKey`.** With
no auth and no rate limit, an unbounded `member_name` is retained three times
over — two normalised copies inside `MemberKey`, a live key in
`UnlimitedClaimStore`, and the as-submitted display string on every `Purchase` —
and nothing evicts, because storage is in-memory. Roughly a hundred 10 MB
requests exhaust the heap.

Putting it in the validator gets the client a `400 validation_failed` with a
field error; putting it in `MemberKey` would surface as a mapped
`IllegalArgumentException`. **Add test `memberNameLengthIsBounded`:** 100
characters accepted, 101 rejected with the field named.

**Only absence is checked here.** Malformed values (`"plan_id": "nope"`,
`"start_date": "2026-13-01"`) never reach this method — Jackson throws during
binding, and §4.5 maps that. Blank-after-trim is the service's job (§1.5), which
is why `""` passes this validator and fails in step 05.

**A wart worth naming rather than hiding:** the wire names are hard-coded here.
The global naming strategy translates `memberName → member_name` for
serialisation, but nothing feeds that back into an error message, so renaming a
DTO field silently desynchronises the `errors` array. Deriving the name from the
`ObjectMapper`'s naming strategy is possible and is over-engineering for three
fields. The mitigation is §4.7's test, which asserts the exact wire names.

### 4.3 `PurchaseAssembler`

Static, matching `PlanAssembler` (step 03):

```java
public static PurchaseResponse toResponse(Purchase p) {
    return new PurchaseResponse(
        p.id(), p.memberName(),
        new PlanSnapshotResponse(p.plan().planId(), p.plan().name(),
                                 p.plan().type().name().toLowerCase(Locale.ROOT),
                                 p.plan().priceCents(), p.plan().credits()),
        p.amountCents(),
        p.status().name().toLowerCase(Locale.ROOT),
        p.startDate(), p.transactionId(), p.createdAt());
}
```

**`memberKey` is deliberately absent from the response** (TRD §3.3) — it is an
internal matching concern and the member never typed it.

### 4.4 `PurchaseController`

```java
@RestController
@RequestMapping("/v1/memberships/purchases")
public final class PurchaseController {

    private final PurchaseService service;

    PurchaseController(PurchaseService service) { this.service = service; }

    @PostMapping
    ResponseEntity<PurchaseResponse> create(@RequestBody PurchaseRequest request) {
        List<FieldErrorResponse> errors = PurchaseRequestValidator.validate(request);
        if (!errors.isEmpty()) {
            throw new RequestValidationException(errors);
        }
        Purchase purchase = service.purchase(request.memberName(), request.planId(), request.startDate());
        return ResponseEntity
            .created(URI.create("/v1/memberships/purchases/" + purchase.id()))
            .body(PurchaseAssembler.toResponse(purchase));
    }

    @GetMapping("/{id}")
    PurchaseResponse get(@PathVariable UUID id) {
        return PurchaseAssembler.toResponse(service.getById(id));
    }
}
```

**The controller holds no rule and makes no status decision.** It validates
shape, delegates, and builds a `201` with a `Location` header. Every failure
leaves via an exception and is given its status by §4.5 — which is NFR-6 in a
twenty-line class. Delete this file and replace it with a CLI and not one rule
moves.

### 4.5 `ApiExceptionHandler` — the single table

```java
@RestControllerAdvice
class ApiExceptionHandler {

    private record Mapping(HttpStatus status, String code) { }

    // Map.ofEntries, NOT Map.of — see the note below.
    private static final Map<Class<? extends Throwable>, Mapping> MAPPINGS = Map.ofEntries(
        entry(PlanNotFoundException.class,              new Mapping(NOT_FOUND,        "plan_not_found")),
        entry(PurchaseNotFoundException.class,          new Mapping(NOT_FOUND,        "purchase_not_found")),
        entry(PlanInactiveException.class,              new Mapping(CONFLICT,         "plan_inactive")),
        entry(UnlimitedMembershipExistsException.class, new Mapping(CONFLICT,         "unlimited_membership_exists")),
        entry(PlanSoldOutException.class,               new Mapping(CONFLICT,         "plan_sold_out")),
        entry(BlankMemberNameException.class,           new Mapping(BAD_REQUEST,      "validation_failed")),
        entry(StartDateInPastException.class,           new Mapping(BAD_REQUEST,      "start_date_in_past")),
        entry(PaymentDeclinedException.class,           new Mapping(PAYMENT_REQUIRED, "payment_declined")),
        entry(PaymentGatewayUnavailableException.class, new Mapping(BAD_GATEWAY,      "payment_gateway_error")),
        // §1.7 fallback: an adapter that cannot tell the two apart throws the base type.
        entry(PaymentFailedException.class,             new Mapping(PAYMENT_REQUIRED, "payment_declined")));
```

> **`Map.of` caps at ten key/value pairs and this table has exactly ten.**
> Tier 2's `purchase_already_cancelled` (D-10) would be the eleventh and would
> not compile. `Map.ofEntries` has no such limit, so use it from the start
> rather than discovering the cliff while adding a stretch goal.

**Lookup is by exact class, not `instanceof`.** With the two payment subtypes
extending `PaymentFailedException` (step 04), a hierarchy walk would need
ordering rules to stop the base row shadowing them. Exact matching plus an
explicit base row is unambiguous and reads in the same order as AC-10.

```java
    @ExceptionHandler(RuntimeException.class)
    ProblemDetail handleDomain(RuntimeException ex, HttpServletRequest request) {
        Mapping mapping = MAPPINGS.get(ex.getClass());
        if (mapping == null) {
            log.error("unhandled exception", ex);                    // full detail to the log
            mapping = new Mapping(INTERNAL_SERVER_ERROR, "internal_error");
            return problem(mapping, "Unexpected error", request);    // and NOTHING to the client
        }
        return problem(mapping, ex.getMessage(), request);
    }

    @ExceptionHandler(RequestValidationException.class)
    ProblemDetail handleValidation(RequestValidationException ex, HttpServletRequest request) {
        ProblemDetail pd = problem(new Mapping(BAD_REQUEST, "validation_failed"),
                                   "The request contains " + ex.errors().size() + " invalid field(s).",
                                   request);
        pd.setProperty("errors", ex.errors());                       // F-2: batched, shape only
        return pd;
    }

    @ExceptionHandler({HttpMessageNotReadableException.class,
                       MethodArgumentTypeMismatchException.class})
    ProblemDetail handleBinding(Exception ex, HttpServletRequest request) {
        return problem(new Mapping(BAD_REQUEST, "validation_failed"),
                       "Request could not be read.", request);       // no parser internals leaked
    }

    /** V-6: codes whose detail must not echo anything the caller supplied. */
    private static final Map<String, String> GENERIC_DETAIL = Map.of(
        "unlimited_membership_exists",
        "This member already holds an active unlimited membership.");

    private static ProblemDetail problem(Mapping m, String detail, HttpServletRequest request) {
        ProblemDetail pd = ProblemDetail.forStatus(m.status());
        pd.setTitle(titleFor(m.code()));
        pd.setDetail(GENERIC_DETAIL.getOrDefault(m.code(), detail));
        pd.setType(URI.create("urn:glofox:problem:" + m.code().replace('_', '-')));
        pd.setInstance(URI.create(request.getRequestURI()));
        pd.setProperty("code", m.code());
        return pd;
    }
}
```

Four things worth defending:

1. **The `500` branch logs everything and returns nothing.** `ex.getMessage()`
   on an unexpected exception can carry a stack-trace fragment, a SQL string, or
   a file path. Domain exceptions get their message through because step 05 wrote
   those messages deliberately; unknown ones do not.
2. **`setInstance` is explicit** because this advice does not extend
   `ResponseEntityExceptionHandler`. *Alternative considered:* extending it would
   handle the binding exceptions for free and populate `instance` — at the cost
   of overriding `handleExceptionInternal` to inject `code`, and more framework
   coupling to explain. Four explicit handlers are easier to walk through aloud.
3. **`type` is a URN derived from `code`** (`plan_sold_out` →
   `urn:glofox:problem:plan-sold-out`), so the two can never disagree. §3.5
   acknowledged the `type`/`code` redundancy; deriving one from the other is what
   keeps it honest.
4. **The `errors` array appears only on `validation_failed`** (§3.5). Every other
   problem concerns the request as a whole.
5. **`unlimited_membership_exists` gets a generic `detail` (V-6).** Echoing the
   member key would turn an unauthenticated `409` into a membership-existence
   oracle: probe a name, learn whether that person is a member. The key is
   logged, not returned, and the machine-readable `code` is unchanged so clients
   lose nothing. For this one code, "a useful detail for the client" and "a
   useful detail for an attacker" are the same string — which is why it is the
   only entry in `GENERIC_DETAIL` rather than a blanket policy.

**Add test `membershipConflictDoesNotEchoTheMemberName`:** make the service
throw `UnlimitedMembershipExistsException(new MemberKey("Alice"))` and assert the
response body contains neither `Alice` nor `alice`.

**Content type.** Returning `ProblemDetail` makes Spring emit
`application/problem+json` automatically — asserted in §4.7 rather than assumed.

### 4.6 The 400-vs-404 boundary (TRD §3.4)

| Request | Status | `code` |
|---|---|---|
| `/purchases/not-a-uuid` | `400` | `validation_failed` — could never name a purchase |
| `/purchases/{valid unknown uuid}` | `404` | `purchase_not_found` — a sensible question, negative answer |

The `400` arrives via `MethodArgumentTypeMismatchException`, thrown by Spring
*before* the controller body runs. Without §4.5's explicit handler Spring renders
a bare `400` with no `code`, which is the kind of gap only a test finds.

### 4.7 `PurchaseControllerTest` — T-6, T-7, and all of AC-10

```java
@WebMvcTest(PurchaseController.class)
class PurchaseControllerTest {
    @MockitoBean PurchaseService service;      // Boot 4: NOT @MockBean
    @Autowired MockMvc mvc;
}
```

**The service is mocked here, unlike step 03's real stores — and the contrast is
deliberate.** This test is about the HTTP contract: statuses, codes, JSON shape.
The rules are step 05's business and already covered there with real stores.
Mocking lets a single test make the service throw each of nine exceptions, which
is the only practical way to observe every AC-10 row.

| Test | Asserts |
|---|---|
| `createReturns201WithLocationAndBody` | `201`; `Location` is `/v1/memberships/purchases/{id}`; `amount_cents` is `8000`; `plan.price_cents` nested; `status` is `"active"` |
| `responseUsesSnakeCaseAndOmitsMemberKey` | body has `member_name`, `amount_cents`, `transaction_id`, `created_at`; body has **no** `memberKey` and no `member_key` |
| `missingFieldsReturnAllErrorsAtOnce` | `{}` → `400`, `code` is `validation_failed`, `errors` has **3** entries with fields `member_name`, `plan_id`, `start_date` |
| `malformedJsonReturns400` | `{` → `400 validation_failed`; detail does **not** contain `com.fasterxml` or `tools.jackson` |
| `unparseableDateReturns400` | `"start_date": "2026-13-01"` → `400 validation_failed` |
| `nonUuidPathReturns400` | `GET /purchases/not-a-uuid` → `400 validation_failed` |
| `unknownPurchaseReturns404` | service throws `PurchaseNotFoundException` → `404 purchase_not_found` |
| `roundTripReturnsPurchase` | `GET` on a known id returns the same body shape as `POST` | **T-6** |
| `problemJsonContentType` | any error response is `application/problem+json` |
| `typeUrnMatchesCode` | `type` is `urn:glofox:problem:plan-sold-out` when `code` is `plan_sold_out` |
| `internalErrorLeaksNothing` | service throws `IllegalStateException("db password is hunter2")` → `500 internal_error`, and the body does **not** contain `hunter2` |

**Plus one parameterised test covering the whole of AC-10** — nine rows, one
`@MethodSource`, mirroring the table:

```java
static Stream<Arguments> ac10() {
    return Stream.of(
        arguments(new PlanNotFoundException(ID),              404, "plan_not_found"),
        arguments(new PurchaseNotFoundException(ID),          404, "purchase_not_found"),
        arguments(new PlanInactiveException(LEGACY),          409, "plan_inactive"),
        arguments(new UnlimitedMembershipExistsException(KEY),409, "unlimited_membership_exists"),
        arguments(new PlanSoldOutException(PROMO),            409, "plan_sold_out"),
        arguments(new BlankMemberNameException(),             400, "validation_failed"),
        arguments(new StartDateInPastException(YESTERDAY),    400, "start_date_in_past"),
        arguments(new PaymentDeclinedException("declined"),   402, "payment_declined"),
        arguments(new PaymentGatewayUnavailableException("x"),502, "payment_gateway_error"));
}
```

TRD §3.6 claimed the code table is "isomorphic to PRD AC-10 so a reviewer can
diff them by eye". This test is that claim made executable: add an AC-10 row
without a mapping and the suite fails.

## 5. Verification

```bash
cd /Users/karthikeyanchandrarajan/Documents/GitHub/learn

# 1. Full suite
./gradlew test --console=plain

# 2. This step
./gradlew test --tests '*PurchaseControllerTest' --console=plain

# 3. END TO END — the full core API, against a running app
./gradlew bootRun --console=plain > /tmp/boot.log 2>&1 &
until grep -q "Started MembershipsApplication" /tmp/boot.log; do sleep 1; done

TOMORROW=$(date -v+1d +%Y-%m-%d)     # macOS; GNU: date -d tomorrow +%F

# 201 + Location
curl -isS -X POST localhost:8080/v1/memberships/purchases \
  -H 'Content-Type: application/json' \
  -d "{\"member_name\":\"Alice\",\"plan_id\":\"11111111-1111-1111-1111-111111111111\",\"start_date\":\"$TOMORROW\"}" \
  | sed -n '1p;/^location:/Ip'
# expect: HTTP/1.1 201  +  location: /v1/memberships/purchases/<uuid>

# 409 — second unlimited for the same member, case-insensitively
curl -sS -X POST localhost:8080/v1/memberships/purchases -H 'Content-Type: application/json' \
  -d "{\"member_name\":\" ALICE \",\"plan_id\":\"11111111-1111-1111-1111-111111111111\",\"start_date\":\"$TOMORROW\"}" \
  | python3 -m json.tool
# expect: status 409, code unlimited_membership_exists

# 409 — inactive plan
curl -sS -o /dev/null -w '%{http_code}\n' -X POST localhost:8080/v1/memberships/purchases \
  -H 'Content-Type: application/json' \
  -d "{\"member_name\":\"Bob\",\"plan_id\":\"44444444-4444-4444-4444-444444444444\",\"start_date\":\"$TOMORROW\"}"
# expect: 409

# 400 — past start_date (AC-11), and the README's own stale example date
curl -sS -X POST localhost:8080/v1/memberships/purchases -H 'Content-Type: application/json' \
  -d '{"member_name":"Bob","plan_id":"22222222-2222-2222-2222-222222222222","start_date":"2026-09-01"}' \
  | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["status"], d["code"])'
# expect: 400 start_date_in_past   <-- the brief's example payload, deliberately rejected

# 400 — all three fields missing, batched
curl -sS -X POST localhost:8080/v1/memberships/purchases \
  -H 'Content-Type: application/json' -d '{}' \
  | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["code"], len(d["errors"]))'
# expect: validation_failed 3

# 400 vs 404 boundary
curl -sS -o /dev/null -w '%{http_code} ' localhost:8080/v1/memberships/purchases/not-a-uuid
curl -sS -o /dev/null -w '%{http_code}\n' localhost:8080/v1/memberships/purchases/99999999-9999-9999-9999-999999999999
# expect: 400 404

# problem+json
curl -sS -o /dev/null -w '%{content_type}\n' localhost:8080/v1/memberships/purchases/not-a-uuid
# expect: application/problem+json

pkill -f MembershipsApplication

# 4. The boundary still holds — service/ and domain/ remain framework-free
grep -rn "org\.springframework\|jackson" \
     src/main/java/com/glofox/memberships/{domain,store,storage,service} \
  && echo "FAIL" || echo "OK"

# 5. No new dependencies
git diff 32ed602 -- build.gradle.kts    # expect: empty
```

**Done when:** every `curl` matches, the suite is green, and `git status` shows 9
new files and 0 modified.

## 6. Notes for the next step

Step 07 is the headline: T-1 and T-3 with the 500-virtual-thread harness. It
drives `PurchaseService` directly with real stores and a `CountingGateway`, not
through HTTP — spinning 500 concurrent requests through `MockMvc` would add
servlet-thread variables to a test about the store's atomicity (§5.1).

Nothing in this step is on T-1's path, which is the point of having built the
atomicity below the web layer.

```
==================================================
CURRENT PHASE: Phase 3 [Modular Implementation Plan] -> Step 06
LAST APPROVED: step_05 + findings F-6 and F-7 applied
NEXT STEP: Review step_06. On approval -> step_07_concurrency_harness.md
==================================================
```
