# Engineering Decisions

This document is a reviewer-oriented guide to the service’s invariants, trade-offs, and test evidence. It describes a take-home implementation, not a production-ready payment system. A successful database checkout represents payment success; no payment provider is integrated.

## At a Glance

- **Stack:** Java 21, Spring Boot 3.4.5, Spring Data JPA, H2 2.3.232, Maven.
- **Checkout guarantee:** At most one order per cart and per `(cart, idempotency key)`; not exactly-once execution.
- **Inventory guarantee:** Checkout uses an atomic conditional stock decrement; cart edits do not reserve stock.
- **Report strategy:** One SQL statement aggregates report fields; `REPEATABLE_READ` is an additional transaction boundary.
- **Latest recorded full verification:** `mvn clean verify -q` on Java 21.0.8 passed 62 tests across 18 classes. PostgreSQL/Testcontainers is not verified because the Docker daemon is unavailable.
- **Payment:** Successful checkout is treated as payment success; no payment provider is integrated.

## Contents

1. [Persistence and schema](#persistence-and-schema)
2. [Inventory concurrency](#inventory-concurrency)
3. [Cart edits and checkout ordering](#cart-edits-and-checkout-ordering)
4. [Idempotency](#idempotency)
5. [Checkout transaction and persistence context](#checkout-transaction-and-persistence-context-behavior)
6. [Coupons and milestone retries](#coupons-and-milestone-retries)
7. [Money and order snapshots](#money-and-order-snapshots)
8. [Errors and database classification](#errors-and-database-classification)
9. [Report snapshot](#report-snapshot)
10. [Configuration](#configuration)
11. [Language and framework choice](#language-and-framework-choice)
12. [AI use and production concerns](#ai-use-and-production-concerns)
13. [Multi-instance and production evolution](#multi-instance-and-production-evolution)
14. [Invariants and evidence](#invariants-and-evidence)
15. [Verification status](#verification-status)
16. [Interview check](#interview-check)

## Decisions

### Persistence and schema

- **Context:** Orders, inventory, idempotency claims, and coupon redemption must commit or roll back together.
- **Options:** In-memory state; ORM-managed state without database constraints; relational persistence with database constraints.
- **Choice:** Spring Data JPA/Hibernate with H2 2.3.232 for the required local/test stack, explicit `schema.sql`, named unique/FK/CHECK constraints, and stored order-line snapshots.
- **Why:** Race-sensitive invariants need database enforcement and transaction rollback, not process-local checks.
- **Consequences:** H2 is convenient for deterministic tests but its PostgreSQL compatibility mode does not prove PostgreSQL concurrency behavior. H2/PostgreSQL production parity remains unverified.

### Inventory concurrency

- **Context:** Two carts can request the last available units concurrently.
- **Options:** Read stock and later write it; reserve stock during cart editing; conditionally decrement stock in the checkout transaction.
- **Choice:** Cart edits perform a friendly availability check without reservation. Checkout performs `UPDATE products SET stock = stock - :quantity WHERE id = :id AND stock >= :quantity` in sorted product-ID order and rolls back if any line fails.
- **Why:** The conditional update is the authoritative stock guard and prevents negative stock without making cart edits reservations.
- **Consequences:** H2 last-stock concurrency coverage passes. H2 reported a table/product-row lock timeout when two checkout transactions contended on the same product row; recognized lock errors now map to a retryable response. PostgreSQL behavior still requires a PostgreSQL run.

### Cart edits and checkout ordering

- **Context:** An edit racing checkout must either be included in the order or rejected after checkout.
- **Options:** Lock individual item rows; lock the cart parent row before loading/modifying items; accept a stale read and reconcile later.
- **Choice:** Cart mutations call `findByIdForUpdate` before reading cart items. Checkout performs conditional `OPEN -> CHECKED_OUT` before loading lines.
- **Why:** Both operations synchronize through the same cart row.
- **Consequences:** The SQL inspector records thread name, transaction name, and SQL; the isolated mutation test verifies the `FOR UPDATE` select is the first cart-table statement and precedes cart-item access. The deterministic cart-edit-versus-checkout race asserts that order lines equal final cart lines and the late edit is either included or rejected.

### Idempotency

- **Context:** Client retries must not create another order or deduct stock twice.
- **Options:** In-memory request cache; unique `(cart_id, key)` claim and committed replay; claim only after order creation.
- **Choice:** Canonicalize once, hash a versioned length-prefixed encoding, insert/flush the named unique claim before cart mutation, and set `order_id` with an explicit update by record ID. The non-transactional facade replays only a positively identified idempotency-constraint conflict whose committed record has the same hash and a stored order.
- **Why:** The database uniqueness guard arbitrates concurrent requests; the transaction owns claim, cart transition, stock, order, and coupon changes.
- **Consequences:** Same-key concurrent retry, payload mismatch, failure rollback, and persisted non-null order linkage are tested. The guarantee is at most one order per cart and `(cart, key)`, not exactly-once execution. Re-read visibility under a PostgreSQL transaction race remains unverified.

### Checkout transaction and persistence-context behavior

- **Context:** Failed validation, stock updates, order creation, or coupon redemption must not partially commit.
- **Options:** Split business actions across transactions; catch errors and compensate; keep one service-proxy transaction and let unchecked failures roll it all back.
- **Choice:** `CheckoutFacade` calls the separate `@Transactional CheckoutTransactionalService`. Product/coupon checkout reads use scalar projections; cart lines load after the cart transition; stock and coupon updates are conditional bulk operations; order lines are persisted as snapshots; idempotency completion updates by row ID.
- **Why:** The transaction boundary and operation order are explicit and testable. Scalar projections avoid using managed Product/Coupon values after bulk updates.
- **Consequences:** Price mismatch, empty cart, multi-line insufficient stock, failure immediately after coupon redemption, and same-key recovery are covered with final persisted-state assertions. A test-only JDBC fault injection executes the real coupon update in the active transaction before throwing.

### Coupons and milestone retries

- **Context:** Admin generation calls can compete, and random codes can collide.
- **Options:** One transaction with an internal retry loop; separate transaction per bounded attempt; application-only milestone checks.
- **Choice:** `CouponAdminFacade` owns independent milestone-conflict and code-collision budgets. Each attempt calls the separate proxied `CouponGenerationService.tryGenerate`, which recounts PLACED orders and existing milestones, selects the lowest unrewarded eligible milestone, and flushes a freshly generated code. Named uniqueness constraints remain the final guards.
- **Why:** A transaction that failed uniqueness is unusable for retry, and one conflict type must not consume the other type's budget.
- **Consequences:** Real H2 code collision, exhaustion, mixed milestone-then-code conflict, and concurrent admin tests pass. A code retry budget exhaustion maps to `RETRYABLE_CONFLICT`; operators should treat repeated code collisions as a code-space/configuration issue.

### Money and order snapshots

- **Context:** Money must be stable across cart display, checkout, persisted order, and reports.
- **Options:** Floating point; recompute totals in reporting; store normalized decimal snapshots.
- **Choice:** `BigDecimal`, USD, scale 2, `HALF_UP`; optional expected total is the final post-discount total and is normalized before comparison/hash. Orders store gross, discount, net, and immutable product-line snapshots. JSON response money is a two-decimal string.
- **Why:** This avoids binary floating-point drift and keeps historical totals independent of later catalog edits.
- **Consequences:** API tests verify string money and report totals reconcile stored order values. There is no product-price update endpoint; a price update landing mid-checkout is out of scope.

### Errors and database classification

- **Context:** A uniqueness error, lock timeout, validation error, and unknown database failure must not be conflated.
- **Options:** Map broad exception classes/messages to retry; positively identify known constraints/lock failures; expose raw database errors.
- **Choice:** Named unique constraints are matched specifically for idempotency and coupon retry paths. Recognized Spring/Hibernate lock failures and observed/standard SQLStates are mapped to `409 RETRYABLE_CONFLICT` with `Retry-After: 1`. Other failures return a safe `500 INTERNAL_ERROR` ProblemDetail without database internals.
- **Why:** False replay/retry classification can hide data corruption or unrelated database failures.
- **Consequences:** H2 lock timeout evidence is H2 `JdbcSQLTimeoutException`, SQLState `HYT00`, error code `50200`, surfaced as Spring `PessimisticLockingFailureException` / Hibernate `PessimisticLockException`; unique SQLState is `23505`, FK `23506`, CHECK `23513`. An opposite-row-lock-order H2 test also resolved as `HYT00`/`50200`, not a distinct deadlock SQLState. PostgreSQL deadlock behavior remains unverified.

### Report snapshot

- **Context:** Every report field must describe one consistent view and every product must appear.
- **Options:** Multiple repository counts; multiple reads under read-only transaction; one aggregate SQL statement plus a read-only isolation boundary.
- **Choice:** A single SQL statement derives product quantities from catalog rows and aggregates stored order totals and coupon counts. `ReportService` also uses read-only `REPEATABLE_READ`.
- **Why:** One statement provides a single statement-level view; the isolation declaration is an additional boundary, not evidence by itself.
- **Consequences:** Sequential reconciliation tests pass. A coordinated reader test queries while checkout transactions are paused after writes and before commit, checks each response against the prior committed snapshot, records five distinct counts, then confirms the final committed count. This establishes the tested H2 scenario only, not PostgreSQL snapshot equivalence.

### Configuration

- **Context:** Invalid reward or lock settings must fail startup instead of reaching business logic.
- **Options:** Parse raw values ad hoc; bind and validate configuration properties.
- **Choice:** `@ConfigurationProperties` records with Bean Validation for `rewards.*` and `app.db.lock-timeout-ms`; the validated timeout is appended to the JDBC URL.
- **Why:** Binding and startup validation centralize defaults and fail-fast behavior.
- **Consequences:** Invalid, missing, and out-of-range settings and valid defaults are covered by context-run/integration tests. H2 does not expose the effective lock timeout through the tested information-schema query, so read-back is unverified.

### Language and framework choice

- **Context:** The role description emphasizes TypeScript/Python/Go, while the take-home explicitly fixes Java 21, Spring Boot 3, Maven, JPA, and H2.
- **Options:** Use a preferred language from the role description; follow the stack already communicated for the assignment.
- **Choice:** Java 21 with Spring Boot 3 and Spring Data JPA.
- **Why:** It follows the agreed assignment stack and provides explicit transaction proxies, declarative validation, and repository/database integration suitable for demonstrating these invariants.
- **Consequences:** This choice optimizes for the communicated evaluation constraints rather than claiming Java is universally preferable to the role’s other languages.

### AI use and production concerns

- **Context:** AI assistance can accelerate implementation but can also introduce invalid test assumptions.
- **Options:** Accept generated changes without validation; use AI-assisted drafts and verify behavior against tests/runtime evidence.
- **Choice:** AI was used to inspect requirements, draft and modify implementation/tests/docs, and debug observed failures. Every stated verification result below comes from an executed command or test report.
- **Why:** The work must be explainable and evidence-based for a live review.
- **Consequences:** Genuine corrections made during this work are listed in `AI_LOG.md`. Production follow-up includes PostgreSQL/Testcontainers, authentication/authorization, durable database operations, real payment integration, and operational monitoring.

### Multi-instance and production evolution

- **Context:** A single application process is not a safe authority for inventory, idempotency, or coupon uniqueness when multiple service instances are deployed.
- **Options:** Keep correctness state in process memory; coordinate through a shared relational database; add a distributed lock service.
- **Choice:** Keep race-sensitive state in the database: conditional stock updates, unique idempotency/order/milestone/code constraints, and conditional coupon redemption. For production, validate the same approach on PostgreSQL with migrations and add authentication/authorization; do not add a separate distributed lock until an observed requirement needs it.
- **Why:** Database constraints and transactions are shared across application instances and already enforce the documented invariants.
- **Consequences:** H2 verifies local behavior only. Production deployment, PostgreSQL behavior, schema migration strategy, and operational failover are not established by this take-home.

## Invariants and Evidence

| Invariant | Enforcement | Test/evidence |
| --- | --- | --- |
| Stock never goes below zero | Conditional product decrement; `ck_product_stock_non_negative` | `Phase4IntegrationTest.competingCheckoutsCannotOversellAvailableStock` |
| One successful order per cart | `uk_order_cart_id`; checkout cart transition | Checkout integration tests; H2 constraint findings |
| Same key replays the same order | `uk_idempotency_cart_key`; request hash; explicit `order_id` update | `CheckoutPhase3Test`, concurrent same-key test |
| Failed checkout leaves no partial state | One transaction; unchecked business failures | Price, empty cart, multi-line stock, coupon fault rollback tests |
| Coupon redeemed at most once | Conditional AVAILABLE-to-REDEEMED update | `CouponRedemptionConcurrencyTest` |
| One coupon per milestone and code | `uk_coupon_milestone`, `uk_coupon_code` | Retry and concurrent admin tests; H2 constraint findings |
| Cart lines stop changing after checkout | Parent cart `FOR UPDATE`; status check | HTTP mutation test verifies unchanged line; SQL-order test verifies first table access |
| Report totals reconcile stored data | Single SQL statement; stored order and line snapshots | Sequential API/report test and coordinated reader/writer test |
| Invalid settings fail startup | Validated configuration-properties records | `ConfigurationPropertiesValidationTest`, `DatabaseConfigurationIntegrationTest` |
| Canonical request hashes are unambiguous | `CanonicalCheckoutRequest`, `CheckoutRequestHasher` | `CanonicalCheckoutRequestTest` |
| Pricing uses decimal HALF_UP and caps discounts | Pure `CheckoutPricing` functions | `CheckoutPricingTest` |
| Cart quantity merge is bounded and overflow-safe | `CartService.add`, stock check, `Math.addExact` | `CartQuantityBoundaryTest` |

## Verification Status

### VERIFIED

Run these commands from the repository root. `-Dtest=...` selects the named tests; `-q` only reduces Maven’s console output.

1. **Lock classification and safe errors**

   Command: `mvn test -q -Dtest=DatabaseLockClassificationTest,CheckoutFacadeClassificationTest,ErrorHandlerTest`

   **Observed:** Passed. The H2 lock timeout was recognized; facade mapping, unknown-error handling, and safe ProblemDetail responses passed.

2. **Constraint findings**

   Command: `mvn test -q -Dtest=DatabaseConstraintFindingsTest`

   **Observed:** Passed. Named unique/FK/CHECK constraints and SQLStates were asserted against H2 2.3.232.

3. **Canonical requests, pricing, and cart quantity boundaries**

   Command: `mvn test -q -Dtest=CanonicalCheckoutRequestTest,CheckoutPricingTest,CartQuantityBoundaryTest`

   **Observed:** Passed. Canonical encoding, money rounding/bounds, merged stock checks, overflow, and maximum quantity were exercised.

4. **Cart SQL lock ordering**

   Command: `mvn test -q -Dtest=CartLockSqlOrderTest`

   **Observed:** Passed. Thread- and transaction-tagged SQL shows the parent-cart `FOR UPDATE` query precedes cart-item statements.

5. **Checkout, API, and coupon redemption**

   Command: `mvn test -q -Dtest=CheckoutPhase3Test,CheckoutApiContractTest,CouponRedemptionConcurrencyTest`

   **Observed:** Passed. Checkout rollback, API behavior, persisted state, and concurrent coupon redemption were exercised.

6. **Full clean build and test suite**

   Command: `JAVA_HOME="/Users/shivamkotnala/Library/Java/JavaVirtualMachines/ms-21.0.8/Contents/Home" PATH="/Users/shivamkotnala/Library/Java/JavaVirtualMachines/ms-21.0.8/Contents/Home/bin:$PATH" mvn clean verify -q`

   **Observed:** Passed on Java 21.0.8: 62 tests across 18 classes, 0 failures, 0 errors, 0 skipped. This is the latest recorded full-suite run; subsequent changes to this file only improve documentation formatting.

### DESIGN INTENT

- Unknown database failures are not classified as replay or retryable lock failures; they use the safe generic 500 response.
- `REPEATABLE_READ` supplements the report’s single-statement snapshot strategy. The H2 reader/writer test validates the controlled scenario only.
- H2 lock timeout URL setting is effective by configuration intent; effective timeout read-back is unavailable from H2 information schema.

### DEFERRED

- H2 opposite-row-lock-order cycle was observed as lock timeout (`HYT00`/`50200`), not a distinct deadlock error. PostgreSQL deadlock creation/classification has not been demonstrated.
- H2 PostgreSQL compatibility mode is not evidence of PostgreSQL isolation, locking, or unique-conflict timing. Testcontainers/PostgreSQL validation is blocked because Docker is installed but the daemon socket `/Users/shivamkotnala/.docker/run/docker.sock` is unavailable.
- Constraint findings cover the named idempotency/order/milestone/code uniqueness constraints, cart-item FK, and product stock CHECK. Other FK/CHECK constraints and every possible database failure path are not exhaustively probed.
- Production authentication/authorization, durable PostgreSQL deployment, payment-provider integration, and operational telemetry are deferred.

## What I Would Examine in Two More Hours

1. Exercise the deterministic cart-edit-versus-checkout and concurrent redemption/generation cases against PostgreSQL/Testcontainers after Docker is available.
2. Induce a distinct PostgreSQL deadlock and verify the exact exception mapping and rollback state.
3. Expand constraint classification tests to the remaining FK/CHECK constraints and add automated OpenAPI/runtime schema comparison.
4. Revisit production authentication, durable database operations, payment integration, and telemetry before deployment.

## Interview Check

Use these as practice prompts. For each answer, explain the invariant, identify the code or database operation that enforces it, and describe the final persisted state for both the winner and loser.

1. **Last unit of stock:** Two different carts request the last unit at the same time. Which SQL operation chooses the winner? What happens to the losing checkout’s cart, order, idempotency record, and stock?
2. **Repeated checkout:** A client retries after a timeout with the same key and payload. How does the facade decide to replay, and why is stock not decremented again? What happens if the payload differs?
3. **Failed coupon checkout:** Checkout fails after coupon redemption was attempted. Which writes roll back, and can the same key be retried after fixing the cause?
4. **Competing coupon redemption:** Two checkouts use one available coupon. Which conditional update allows at most one redemption, and what persisted state should the loser observe?
5. **Competing admin generation:** Two admin calls select the same unrewarded milestone. Which unique constraint is the final guard, and how do the separate retry budgets behave?
6. **Cart edit racing checkout:** What are the two acceptable outcomes for the late edit? How does the parent cart lock make the order lines match the final cart lines?
7. **Report during traffic:** What does the coordinated H2 reader/writer test demonstrate about report snapshots? What does it not establish about PostgreSQL isolation and locking?