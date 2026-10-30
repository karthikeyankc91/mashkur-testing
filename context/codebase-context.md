# Codebase Context

Verified by reading the skeleton in this repo, not inferred from the README.
Commit `55036b4` is the unmodified vendor import.

## Tech stack

| | |
|---|---|
| Language | Java, `sourceCompatibility`/`targetCompatibility` 21, `options.release = 21` |
| Local JDK | 22.0.1 (Oracle). Compiles to 21 bytecode; satisfies "Java 21+" |
| Framework | **Spring Boot 4.0.0** (Spring Framework 7) |
| Build | Gradle 9.5.1, Kotlin DSL (`build.gradle.kts`), wrapper committed |
| Dependency mgmt | `io.spring.dependency-management` 1.1.7 |
| Test | JUnit Platform via `spring-boot-starter-webmvc-test` |
| Server | Port 8080 (`application.yml`) |
| Root project | `memberships-task` |

### Dependencies — exactly three

```kotlin
implementation("org.springframework.boot:spring-boot-starter-webmvc")
testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
testRuntimeOnly("org.junit.platform:junit-platform-launcher")
```

**Boot 4 renamed the starters** — `-webmvc` / `-webmvc-test`, not the familiar
`-web` / `-test`. Writing the old names fails resolution.

No Lombok. No MapStruct. No MockMvc helpers beyond the starter. Plain records.

## Directory structure

```
src/main/java/com/glofox/memberships/
├── MembershipsApplication.java              # @SpringBootApplication; logs seeds on ApplicationReadyEvent
├── config/SeedData.java                     # final class, private ctor, immutable List.of
├── payment/PaymentGateway.java              # the port
├── payment/AlwaysSucceedingPaymentGateway.java   # @Component, default bean
└── storage/memory/package-info.java         # empty package, javadoc only
src/test/java/com/glofox/memberships/
└── MembershipsApplicationTests.java         # @SpringBootTest contextLoads()
```

## Exact skeleton contracts

```java
public interface PaymentGateway {
    PaymentResult charge(int amountCents, String memberName);
    record PaymentResult(String transactionId) {}
    class PaymentFailedException extends RuntimeException {
        public PaymentFailedException(String message) { super(message); }
    }
}

public record PlanSeed(UUID id, String name, String type, boolean active,
                       int priceCents, Integer credits, Integer remainingSlots) {}
```

Seeded data: Monthly Unlimited (`1111…`, unlimited, active, 8000, –, –),
10-Class Pack (`2222…`, pack, active, 5000, 10, –), Launch Promo Pack
(`3333…`, pack, active, 2500, 5, **5**), Legacy Plan (`4444…`, unlimited,
**inactive**, 6000, –, –).

## Constraints the skeleton imposes

1. **`PaymentFailedException extends RuntimeException`** — unchecked, despite the
   `@throws` javadoc. Nothing forces a catch, so the default failure mode is a
   leaked `500` over partially-mutated state. This is the exact behaviour
   FR-5(f) tests.
2. **`charge(int, String)` has no idempotency parameter.** Retry safety cannot be
   delegated to the gateway; a retry double-charges.
3. **`SeedData` is `final`, `PLANS` is `List.of`, `PlanSeed` is a record.**
   Mutable inventory cannot live in `SeedData`. The store must own it — which
   usefully forces plan *catalogue* apart from plan *inventory*.
4. **`type` is a raw `String`** (`"unlimited"` / `"pack"`). Map to a domain enum
   at the seeding boundary or carry stringly-typed logic everywhere.
5. **`AlwaysSucceedingPaymentGateway` is `@Component`.** Overriding it in tests
   needs `@MockitoBean` or `@TestConfiguration` + `@Primary` — **`@MockBean` was
   removed in Boot 4.**
6. **No `spring-boot-starter-validation`.** `@Valid` / `@NotBlank` are absent
   from the classpath. Add the starter or hand-roll validation; the README
   prefers "clarity over frameworks for their own sake."
7. **`remainingSlots == null` means unlimited inventory, not zero** — true for
   three of four plans.

## Repo defect found and fixed

The paste placed the wrapper in `./wrapper/` while `gradlew:213` bootstraps from
`$APP_HOME/gradle/wrapper/gradle-wrapper.jar`, and `gradle/` was empty — every
`./gradlew` call failed. Fixed in `9f52e7a`.

## Verified build health

```
./gradlew --version   -> Gradle 9.5.1, JVM 22.0.1
./gradlew test        -> BUILD SUCCESSFUL (contextLoads passes)
```
