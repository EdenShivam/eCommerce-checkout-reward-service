# Uniblox Checkout Rewards Service

Spring Boot checkout service implementing carts, transactional inventory deduction, idempotent orders, administrator-triggered milestone coupons, and a read-only purchase/revenue report. A successful checkout is treated as successful payment; there is no payment provider integration.

## Prerequisites

- JDK 21
- Maven 3.9 or later
- `curl` for the examples below; `jq` is optional for extracting IDs

## Build, Test, Run

```bash
mvn clean verify
mvn spring-boot:run
```

The base URL is `http://localhost:8080`. The OpenAPI contract is in [`docs/openapi.yaml`](docs/openapi.yaml). H2 is in-memory, so data is reset when the application stops.

## Configuration

| Property | Default | Validation |
| --- | --- | --- |
| `rewards.n` | `5` | Integer greater than or equal to 1 |
| `rewards.discount-percent` | `10` | BigDecimal greater than 0 and at most 100 |
| `rewards.generation-max-attempts` | `3` | Integer greater than or equal to 1; milestone-conflict retry budget |
| `rewards.code-max-attempts` | `5` | Integer greater than or equal to 1; coupon-code collision retry budget |
| `app.db.lock-timeout-ms` | `5000` | Integer from 1 through 60000; appended to the H2 JDBC URL as `LOCK_TIMEOUT` |

These values are bound and validated at startup. The test verified H2 does not expose the effective lock timeout through `INFORMATION_SCHEMA.SETTINGS`; the configured URL can be inspected, but database read-back is unavailable in this setup.

## Seed Catalog

The fresh database seeds five products. Identity IDs start at 1: Limited Edition Mug (`18.50`, stock 3), Mechanical Keyboard (`129.99`, stock 20), Wireless Mouse (`49.50`, stock 50), USB-C Dock (`89.00`, stock 40), Laptop Stand (`39.95`, stock 100). Cart-time stock checks are advisory; checkout uses an atomic conditional decrement and is authoritative.

## API Flow

List products and create a cart:

```bash
curl http://localhost:8080/products
curl -s -X POST http://localhost:8080/carts
```

Copy the returned cart `id` into `CART_ID`, then add product 2 and inspect the cart. Checkout requires an `Idempotency-Key` matching `[A-Za-z0-9._:-]{1,128}`.

```bash
export CART_ID='<cart-uuid>'
curl -X POST "http://localhost:8080/carts/$CART_ID/items" \
  -H 'Content-Type: application/json' \
  -d '{"productId":2,"quantity":1}'
curl "http://localhost:8080/carts/$CART_ID"
curl -i -X POST "http://localhost:8080/carts/$CART_ID/checkout" \
  -H 'Idempotency-Key: checkout-demo-001'
```

The first successful checkout returns `201 Created`. Repeating the same key and canonical payload returns the stored order with `200 OK` and `Idempotent-Replayed: true`. A different payload with the same key returns `422 IDEMPOTENCY_KEY_REUSED`. The guarantee is at most one order per cart and per `(cart, idempotency key)`, not exactly-once execution.

Use the returned order `id` with `GET /orders/{orderId}`. To reach the default first reward milestone, complete five successful orders. Each checkout needs its own open cart and a distinct idempotency key. Coupon generation is administrator-triggered: a milestone coupon is not automatically created at the instant the fifth order commits. The order that reaches a milestone cannot use the coupon generated afterward. Each admin call creates one coupon for the lowest eligible unrewarded milestone:

```bash
curl -i -X POST http://localhost:8080/admin/coupons/generate
```

Copy the returned coupon `code`, create another cart, add a product, and redeem it by sending the code and optional expected final payable total:

```bash
curl -X POST "http://localhost:8080/carts/$CART_ID/checkout" \
  -H 'Idempotency-Key: checkout-with-coupon-001' \
  -H 'Content-Type: application/json' \
  -d '{"couponCode":"<COUPON_CODE>","expectedTotal":"116.99"}'
curl http://localhost:8080/admin/report
```

`expectedTotal` is the final amount after discount. Money in API responses is a two-decimal string. Inputs with more than two decimal places are rejected. The report includes every catalog product, including zero-purchase products, and sums stored order gross, discount, and net amounts.

Checkout reads current prices inside its transaction. There is no product-update endpoint; a price edit landing mid-checkout is outside the current API/business scope.

`/admin/*` is administrative but unauthenticated in this take-home. Authentication and authorization are required for production use.

## Main Error Codes

Errors use RFC 7807 `ProblemDetail` with a stable `code`; unexpected server errors use `INTERNAL_ERROR` without stack traces or database details.

| HTTP | Codes |
| --- | --- |
| 400 | `VALIDATION_FAILED` |
| 404 | `PRODUCT_NOT_FOUND`, `CART_NOT_FOUND`, `ORDER_NOT_FOUND` |
| 409 | `INSUFFICIENT_STOCK`, `CART_ALREADY_CHECKED_OUT`, `PRICE_CHANGED`, `COUPON_ALREADY_REDEEMED`, `NO_ELIGIBLE_MILESTONE`, `RETRYABLE_CONFLICT` |
| 422 | `EMPTY_CART`, `COUPON_NOT_FOUND`, `IDEMPOTENCY_KEY_REUSED` |
| 500 | `INTERNAL_ERROR` |

`Retry-After: 1` is returned only with `RETRYABLE_CONFLICT`.

## Status

### VERIFIED

- `mvn test -q -Dtest=DatabaseLockClassificationTest,CheckoutFacadeClassificationTest,ErrorHandlerTest` — passed. The real H2 lock test observed Spring `PessimisticLockingFailureException` / Hibernate `PessimisticLockException` over H2 `JdbcSQLTimeoutException`, SQLState `HYT00`, error code `50200`, and classified it as retryable.
- `mvn test -q -Dtest=DatabaseConstraintFindingsTest` — passed. H2 2.3.232 reported named unique constraints with SQLState `23505`, FK with `23506`, and CHECK with `23513`.
- `mvn test -q -Dtest=CanonicalCheckoutRequestTest,CheckoutPricingTest,CartQuantityBoundaryTest` — passed. Canonical hash/normalization, HALF_UP pricing bounds, add-merge stock checks, overflow, and per-request quantity limits are covered.
- Focused checkout and API tests observed unknown-cart `404`, checked-out cart mutation `409`, malformed JSON `400`, replay `200`, and the expected persisted state.
- `mvn test -q -Dtest=CartEditCheckoutConcurrencyTest` — passed. The concurrent edit was either included or rejected, and persisted order lines matched the final cart lines.
- `JAVA_HOME="/Users/shivamkotnala/Library/Java/JavaVirtualMachines/ms-21.0.8/Contents/Home" PATH="/Users/shivamkotnala/Library/Java/JavaVirtualMachines/ms-21.0.8/Contents/Home/bin:$PATH" mvn clean verify -q` — passed on Java 21.0.8; 62 tests across 18 classes passed with 0 failures, errors, or skips.

### DESIGN INTENT

- Report fields are returned by one SQL statement, with `REPEATABLE_READ` also declared at the service boundary. Tests coordinate report reads while checkout writes are uncommitted and assert the prior committed snapshot.
- Lock failures are mapped only for recognized Spring/Hibernate lock exceptions or recognized SQLStates/error codes. Unrecognized database failures remain generic errors.
- H2 `LOCK_TIMEOUT` is set from validated configuration; effective timeout read-back is not available from the tested H2 information schema.

### DEFERRED

- An opposite-row-lock-order test on H2 2.3.232 resolved as a lock timeout (`HYT00`/`50200`), not a distinct deadlock SQLState. PostgreSQL deadlock classification remains unverified.
- H2 PostgreSQL-compatibility mode does not establish PostgreSQL isolation, lock, deadlock, or unique-conflict timing. Testcontainers/PostgreSQL validation is blocked in this environment because `docker info` could not connect to `/Users/shivamkotnala/.docker/run/docker.sock`; start Docker and run the PostgreSQL profile/test suite as the next validation step.
- There is no product-price update endpoint; a price edit landing during checkout is outside this service’s current API.
- Admin authentication, real payment integration, and durable production database deployment are out of scope.

See [`DECISIONS.md`](DECISIONS.md) for design rationale, invariant-to-test mapping, and the full verification/deferred record. `AI_LOG.md` records genuine AI-assisted corrections made while testing.