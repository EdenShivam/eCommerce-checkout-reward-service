# AI-Assisted Work Log

This log records concrete AI-generated implementation/test choices that were corrected after examining code or runtime evidence. It is not a list of hypothetical suggestions.

| Date | Initial AI-generated choice | Evidence and correction |
| --- | --- | --- |
| 2026-10-19 | The replay test asserted that the entire shared test database contained exactly one order. | The focused test run showed two orders because another test method had already committed an order. The assertion was changed to count orders for the cart under test. |
| 2026-10-19 | The first report writer/reader test synchronized after each checkout had committed. | That proved sequentially visible totals, not reads concurrent with an in-flight write. The test was replaced with a separate reader querying while checkout was paused before commit, then checked the prior committed snapshot and multiple order counts. |
| 2026-10-19 | The first same-coupon race used the same product row in both carts. | H2 timed out on the shared product row before the coupon update, so that run did not isolate coupon redemption. The test now uses different product rows and coordinates both transactions at the conditional coupon update. |
| 2026-10-19 | The first fault-injection attempt called `callRealMethod()` on a Spring Data repository interface method. | Mockito reported that the abstract repository method has no real implementation. The test now issues the real conditional SQL through transaction-bound `JdbcTemplate`, then throws to verify rollback. |
| 2026-10-19 | The first OpenAPI draft placed the `/products` 500 response under the 200 response content and escaped regex backslashes twice; the checkout DTO also accepted numeric JSON tokens for money despite documenting strings. | YAML structure and parsed regex inspection exposed the contract defects. The response was moved under `responses`, regexes were corrected and exercised, and the checkout boundary now rejects non-string money/coupon tokens with `VALIDATION_FAILED`. |

AI was used to help inspect requirements, draft implementation and test changes, and interpret failures. Test claims in this repository are based on commands actually run; no mutation experiment or AI-output correction beyond the entries above is claimed.