# Phase 2 Commit Plan

Phase 2 introduces products and carts. The history should tell a reviewer how the service moves from a database schema to safe customer-facing cart operations.

## 1. `Add product browsing and safe cart editing`

Include:

- Product, cart, and cart-item JPA mappings
- Product and cart repositories
- Product read endpoints
- Cart create, view, add, update, and remove endpoints
- Shared API error handling
- Parent-cart pessimistic locking for cart mutations

Why this is useful: it gives clients a usable cart flow while ensuring cart edits are serialized through the parent cart row and checked-out carts cannot be changed.

## 2. `Show current prices and line totals when viewing a cart`

Include:

- Product-backed cart-line response DTOs
- Money normalization helper
- Tests for current-price cart totals

Why this is useful: cart views become useful to a customer while keeping clear that cart prices are not reserved and checkout will recalculate them later.

## 3. `Cover cart quantity rules and checked-out cart behavior`

Include:

- Focused controller/service integration tests
- POST merge, PUT absolute quantity, and overflow cases
- Persisted-state assertions for rejected mutations

Why this is useful: it proves invalid products and quantities never enter carts and cart state transitions are enforced by observable behavior, not only HTTP status codes.

## 4. `Prove cart mutations lock before reading cart lines`

Include:

- SQL statement-inspection support
- Isolated cart-lock SQL-order evidence test
- Documentation of observed Hibernate SQL ordering

Why this is useful: it demonstrates the key concurrency invariant required for a cart edit racing checkout: the cart row is locked before cart lines are read or written.

## Current Phase 2 commit

The implemented initial slice is committed as `Add product browsing and safe cart editing`. The remaining commits are intentionally deferred until their code and tests exist.
