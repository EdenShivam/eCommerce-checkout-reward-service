# Uniblox Checkout Rewards Service

Reliable ecommerce checkout and milestone coupon service for the Uniblox take-home.

## Prerequisites

- JDK 21
- Maven 3.9+

## Build and test

```bash
mvn verify
```

## Run locally

```bash
mvn spring-boot:run
```

The local service will run at `http://localhost:8080`. The API contract is in [`docs/openapi.yaml`](docs/openapi.yaml).

## Phase 1 status

This baseline contains the database schema, seed products, validated configuration, and API contract. HTTP endpoint implementations begin in Phase 2.

The configured H2 `LOCK_TIMEOUT` is present in the JDBC URL. Under the tested H2 version, it is not available through `INFORMATION_SCHEMA.SETTINGS`; effective lock-timeout readback is therefore unverified and will be documented as a design limitation.
