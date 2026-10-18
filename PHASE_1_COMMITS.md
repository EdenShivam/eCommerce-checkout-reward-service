# Phase 1 Commit Plan

Phase 1 establishes the Spring Boot baseline, database foundation, validated configuration, and API contract. The history should read like a developer progressively building confidence in the system, not like generated file batches.

## 1. `Set up the Java 21 Spring Boot checkout service`

Include:

- `pom.xml`
- `.gitignore`
- `src/main/java/com/uniblox/checkout/CheckoutRewardsApplication.java`
- Initial `README.md`

Why this is useful: a reviewer can clone the project, import it in IntelliJ, and see a clear Java 21 / Maven / Spring Boot baseline immediately.

## 2. `Create the checkout schema with database-enforced invariants`

Include:

- `src/main/resources/schema.sql`

Why this is useful: it introduces the full relational model and the named constraints that will protect stock, one order per cart, coupon milestones, coupon redemption state, and idempotency records.

## 3. `Seed products for local checkout and inventory testing`

Include:

- `src/main/resources/data.sql`

Why this is useful: it makes the service testable from the first run with five predictable products, including a product with only three units for the later oversell-race test.

## 4. `Validate reward rules and configure the H2 lock timeout`

Include:

- `src/main/resources/application.yml`
- `src/main/java/com/uniblox/checkout/config/AppDatabaseProperties.java`
- `src/main/java/com/uniblox/checkout/config/RewardsProperties.java`
- `src/main/java/com/uniblox/checkout/config/DatabaseConfiguration.java`
- `src/test/java/com/uniblox/checkout/config/ConfigurationPropertiesValidationTest.java`
- `src/test/java/com/uniblox/checkout/config/DatabaseConfigurationIntegrationTest.java`
- `src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker`

Why this is useful: it makes invalid reward and lock-timeout settings fail before business logic can run, and proves the database schema and seed data load. It also records the observed limitation that H2 does not expose its effective lock timeout through `INFORMATION_SCHEMA.SETTINGS`.

## 5. `Document the API contract before implementing endpoints`

Include:

- `docs/openapi.yaml`
- Final Phase 1 updates to `README.md`

Why this is useful: it fixes the public contract before controllers exist, including endpoint shapes, idempotency-key validation, replay behavior, stable errors, and the report reconciliation fields.

## Verification for the final Phase 1 commit

Run with Java 21:

```bash
mvn verify
```

Expected result: all Phase 1 tests pass and Maven reports `BUILD SUCCESS`.

## Current history note

The current repository contains one Phase 1 baseline commit: `e59c349` (`chore: establish phase one checkout baseline`). The five commits above are the intended readable history. Splitting the existing commit requires a deliberate Git-history rewrite, so it has not been done yet.
