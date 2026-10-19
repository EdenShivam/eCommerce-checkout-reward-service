package com.uniblox.checkout.service;

import com.uniblox.checkout.domain.Cart;
import com.uniblox.checkout.domain.CartStatus;
import com.uniblox.checkout.domain.Coupon;
import com.uniblox.checkout.domain.CouponStatus;
import com.uniblox.checkout.repository.CouponRepository;
import com.uniblox.checkout.repository.IdempotencyRecordRepository;
import com.uniblox.checkout.repository.OrderRepository;
import com.uniblox.checkout.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
    "spring.test.mock.mockito.reset=none",
    "spring.datasource.url=jdbc:h2:mem:checkout-phase3-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"
})
class CheckoutPhase3Test {

    @Autowired
    private CheckoutFacade checkoutFacade;

    @Autowired
    private CartService cartService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @MockitoSpyBean
    private CouponRepository couponRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetDatabaseState() {
        jdbcTemplate.update("DELETE FROM idempotency_records");
        jdbcTemplate.update("DELETE FROM coupons");
        jdbcTemplate.update("DELETE FROM order_lines");
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("DELETE FROM cart_items");
        jdbcTemplate.update("DELETE FROM carts");
        jdbcTemplate.update("UPDATE products SET stock = CASE id WHEN 1 THEN 3 WHEN 2 THEN 20 WHEN 3 THEN 50 WHEN 4 THEN 40 WHEN 5 THEN 100 END");
    }

    @Test
    void canonicalCheckoutRequestRejectsInvalidIdempotencyKey() {
        for (String invalid : new String[]{null, "", "   ", "bad key", "abc/def", "é", "key\n", "x".repeat(129)}) {
            assertThatThrownBy(() -> CanonicalCheckoutRequest.of(UUID.randomUUID(), invalid, null, null))
                .as("key: %s", invalid)
                .isInstanceOf(com.uniblox.checkout.api.ApiException.class)
                .extracting("code").isEqualTo("VALIDATION_FAILED");
        }
        assertThat(CanonicalCheckoutRequest.of(UUID.randomUUID(), "a", null, null).idempotencyKey()).isEqualTo("a");
        assertThat(CanonicalCheckoutRequest.of(UUID.randomUUID(), "x".repeat(128), null, null).idempotencyKey()).hasSize(128);
        for (String punctuation : new String[]{".", "_", ":", "-"}) {
            assertThat(CanonicalCheckoutRequest.of(UUID.randomUUID(), punctuation, null, null).idempotencyKey()).isEqualTo(punctuation);
        }
    }

    @Test
    void successfulCheckoutPersistsOrderAndDeductsStock() {
        Cart cart = cartService.create();
        long productId = productRepository.findAll().getFirst().getId();
        cartService.add(cart.getId(), productId, 2);

        var originalStock = productRepository.findById(productId).orElseThrow().getStock();
        var order = checkoutFacade.checkout(cart.getId(), "phase3-key-1", null, null);

        assertThat(order).isNotNull();
        assertThat(orderRepository.findByCartId(cart.getId())).isPresent();
        assertThat(productRepository.findById(productId).orElseThrow().getStock()).isEqualTo(originalStock - 2);
        assertThat(idempotencyRecordRepository.findByCartIdAndKey(cart.getId(), "phase3-key-1"))
            .get().extracting(record -> record.getOrderId()).isEqualTo(order.getId());
    }

    @Test
    void repeatedKeyReplaysStoredOrder() {
        Cart cart = cartService.create();
        long productId = productRepository.findAll().get(1).getId();
        cartService.add(cart.getId(), productId, 1);

        var first = checkoutFacade.checkout(cart.getId(), "phase3-key-2", null, null);
        var second = checkoutFacade.checkout(cart.getId(), "phase3-key-2", null, null);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(orderRepository.findAllByCartId(cart.getId())).hasSize(1);
    }

    @Test
    void failedPriceCheckRollsBackAndSameKeyCanSucceedAfterCorrection() {
        Cart cart = cartService.create();
        long productId = productRepository.findAll().getFirst().getId();
        cartService.add(cart.getId(), productId, 1);
        int stockBefore = productRepository.findById(productId).orElseThrow().getStock();

        assertThatThrownBy(() -> checkoutFacade.checkout(cart.getId(), "phase3-retry", null, java.math.BigDecimal.ZERO))
                .isInstanceOf(RuntimeException.class);

        assertThat(cartService.get(cart.getId()).getStatus()).isEqualTo(CartStatus.OPEN);
        assertThat(productRepository.findById(productId).orElseThrow().getStock()).isEqualTo(stockBefore);
        assertThat(orderRepository.findByCartId(cart.getId())).isEmpty();
        assertThat(idempotencyRecordRepository.findByCartIdAndKey(cart.getId(), "phase3-retry")).isEmpty();

        var successfulRetry = checkoutFacade.checkout(cart.getId(), "phase3-retry", null, null);

        assertThat(orderRepository.findByCartId(cart.getId())).get().extracting(order -> order.getId()).isEqualTo(successfulRetry.getId());
        assertThat(idempotencyRecordRepository.findByCartIdAndKey(cart.getId(), "phase3-retry"))
                .get().extracting(record -> record.getOrderId()).isEqualTo(successfulRetry.getId());
        assertThat(productRepository.findById(productId).orElseThrow().getStock()).isEqualTo(stockBefore - 1);
    }

    @Test
    void sameKeyWithDifferentPayloadIsRejected() {
        Cart cart = cartService.create();
        long productId = productRepository.findAll().get(1).getId();
        cartService.add(cart.getId(), productId, 1);
        checkoutFacade.checkout(cart.getId(), "phase3-different-payload", null, null);

        assertThatThrownBy(() -> checkoutFacade.checkout(cart.getId(), "phase3-different-payload", null, java.math.BigDecimal.ONE))
                .isInstanceOf(com.uniblox.checkout.api.ApiException.class)
                .extracting("code").isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(orderRepository.findAllByCartId(cart.getId())).hasSize(1);
    }

    @Test
    void concurrentSameKeyRequestsCommitOneOrderAndDeductStockOnce() throws Exception {
        Cart cart = cartService.create();
        long productId = productRepository.findAll().get(1).getId();
        cartService.add(cart.getId(), productId, 2);
        int stockBefore = productRepository.findById(productId).orElseThrow().getStock();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<java.util.UUID> first = executor.submit(() -> checkoutAtStart(cart.getId(), "phase3-concurrent-key", ready, start));
            Future<java.util.UUID> second = executor.submit(() -> checkoutAtStart(cart.getId(), "phase3-concurrent-key", ready, start));
            ready.await();
            start.countDown();

            assertThat(first.get()).isEqualTo(second.get());
        }

        assertThat(orderRepository.findAllByCartId(cart.getId())).hasSize(1);
        assertThat(productRepository.findById(productId).orElseThrow().getStock()).isEqualTo(stockBefore - 2);
        assertThat(idempotencyRecordRepository.findByCartIdAndKey(cart.getId(), "phase3-concurrent-key"))
                .get().extracting(record -> record.getOrderId()).isNotNull();
    }

    @Test
    void emptyCartFailureLeavesCartOpenAndDoesNotClaimKey() {
        Cart cart = cartService.create();

        assertThatThrownBy(() -> checkoutFacade.checkout(cart.getId(), "phase3-empty", null, null))
                .isInstanceOf(com.uniblox.checkout.api.ApiException.class)
                .extracting("code").isEqualTo("EMPTY_CART");

        assertThat(cartService.get(cart.getId()).getStatus()).isEqualTo(CartStatus.OPEN);
        assertThat(orderRepository.findByCartId(cart.getId())).isEmpty();
        assertThat(idempotencyRecordRepository.findByCartIdAndKey(cart.getId(), "phase3-empty")).isEmpty();
    }

    @Test
    void laterStockFailureRollsBackEarlierDecrementAndLeavesCouponAvailable() {
        Cart cart = cartService.create();
        long firstProductId = productRepository.findById(1L).orElseThrow().getId();
        long secondProductId = productRepository.findById(2L).orElseThrow().getId();
        cartService.add(cart.getId(), firstProductId, 1);
        cartService.add(cart.getId(), secondProductId, 2);
        Coupon coupon = couponRepository.saveAndFlush(new Coupon("ROLLBACK1", 1, java.math.BigDecimal.TEN, CouponStatus.AVAILABLE));
        jdbcTemplate.update("UPDATE products SET stock = 1 WHERE id = ?", secondProductId);

        assertThatThrownBy(() -> checkoutFacade.checkout(cart.getId(), "phase3-stock-rollback", coupon.getCode(), null))
                .isInstanceOf(com.uniblox.checkout.api.ApiException.class)
                .extracting("code").isEqualTo("INSUFFICIENT_STOCK");

        assertThat(cartService.get(cart.getId()).getStatus()).isEqualTo(CartStatus.OPEN);
        assertThat(productRepository.findById(firstProductId).orElseThrow().getStock()).isEqualTo(3);
        assertThat(productRepository.findById(secondProductId).orElseThrow().getStock()).isEqualTo(1);
        assertThat(couponRepository.findByCode(coupon.getCode()).orElseThrow().getStatus()).isEqualTo(CouponStatus.AVAILABLE);
        assertThat(orderRepository.findByCartId(cart.getId())).isEmpty();
        assertThat(idempotencyRecordRepository.findByCartIdAndKey(cart.getId(), "phase3-stock-rollback")).isEmpty();

        jdbcTemplate.update("UPDATE products SET stock = 2 WHERE id = ?", secondProductId);
        var order = checkoutFacade.checkout(cart.getId(), "phase3-stock-rollback", coupon.getCode(), null);

        assertThat(orderRepository.findByCartId(cart.getId())).get().extracting(saved -> saved.getId()).isEqualTo(order.getId());
        assertThat(productRepository.findById(firstProductId).orElseThrow().getStock()).isEqualTo(2);
        assertThat(productRepository.findById(secondProductId).orElseThrow().getStock()).isZero();
        assertThat(couponRepository.findByCode(coupon.getCode()).orElseThrow().getStatus()).isEqualTo(CouponStatus.REDEEMED);
    }

    @Test
    void failureImmediatelyAfterCouponRedemptionRollsBackEntireCheckout() {
        Cart cart = cartService.create();
        long productId = productRepository.findById(2L).orElseThrow().getId();
        cartService.add(cart.getId(), productId, 1);
        int stockBefore = productRepository.findById(productId).orElseThrow().getStock();
        Coupon coupon = couponRepository.saveAndFlush(new Coupon("FAULTROLLBACK", 1, java.math.BigDecimal.TEN, CouponStatus.AVAILABLE));
        doAnswer(invocation -> {
            String code = invocation.getArgument(0);
            UUID orderId = invocation.getArgument(1);
            int updated = jdbcTemplate.update(
                    "UPDATE coupons SET status = ?, redeemed_order_id = ? WHERE code = ? AND status = ?",
                    CouponStatus.REDEEMED.name(), orderId, code, CouponStatus.AVAILABLE.name());
            if (updated == 1) {
                throw new IllegalStateException("forced failure after coupon redemption update");
            }
            return updated;
        }).when(couponRepository).redeemIfAvailable(eq(coupon.getCode()), any(UUID.class), eq(CouponStatus.AVAILABLE), eq(CouponStatus.REDEEMED));

        try {
            assertThatThrownBy(() -> checkoutFacade.checkout(cart.getId(), "phase3-coupon-fault", coupon.getCode(), null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("forced failure");
        } finally {
            reset(couponRepository);
        }

        assertThat(cartService.get(cart.getId()).getStatus()).isEqualTo(CartStatus.OPEN);
        assertThat(productRepository.findById(productId).orElseThrow().getStock()).isEqualTo(stockBefore);
        assertThat(couponRepository.findByCode(coupon.getCode()).orElseThrow().getStatus()).isEqualTo(CouponStatus.AVAILABLE);
        assertThat(orderRepository.findByCartId(cart.getId())).isEmpty();
        assertThat(idempotencyRecordRepository.findByCartIdAndKey(cart.getId(), "phase3-coupon-fault")).isEmpty();

        var order = checkoutFacade.checkout(cart.getId(), "phase3-coupon-fault", coupon.getCode(), null);

        assertThat(orderRepository.findByCartId(cart.getId())).get().extracting(saved -> saved.getId()).isEqualTo(order.getId());
        assertThat(couponRepository.findByCode(coupon.getCode()).orElseThrow().getStatus()).isEqualTo(CouponStatus.REDEEMED);
    }

    @Test
    void newKeyCannotCreateSecondOrderForCheckedOutCart() {
        Cart cart = cartService.create();
        long productId = productRepository.findAll().get(1).getId();
        cartService.add(cart.getId(), productId, 1);
        checkoutFacade.checkout(cart.getId(), "phase3-first-key", null, null);

        assertThatThrownBy(() -> checkoutFacade.checkout(cart.getId(), "phase3-second-key", null, null))
                .isInstanceOf(com.uniblox.checkout.api.ApiException.class)
                .extracting("code").isEqualTo("CART_ALREADY_CHECKED_OUT");
        assertThat(orderRepository.findAllByCartId(cart.getId())).hasSize(1);
        assertThat(idempotencyRecordRepository.findByCartIdAndKey(cart.getId(), "phase3-second-key")).isEmpty();
    }

    private java.util.UUID checkoutAtStart(UUID cartId, String key, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        return checkoutFacade.checkout(cartId, key, null, null).getId();
    }
}
