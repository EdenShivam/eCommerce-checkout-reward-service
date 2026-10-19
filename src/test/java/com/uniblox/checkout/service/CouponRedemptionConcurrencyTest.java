package com.uniblox.checkout.service;

import com.uniblox.checkout.api.ApiException;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:coupon-redemption-concurrency-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH")
class CouponRedemptionConcurrencyTest {
    @Autowired private CartService cartService;
    @Autowired private CheckoutFacade checkoutFacade;
    @Autowired private ProductRepository productRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private IdempotencyRecordRepository idempotencyRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoSpyBean private CouponRepository couponRepository;

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.update("DELETE FROM idempotency_records");
        jdbcTemplate.update("DELETE FROM coupons");
        jdbcTemplate.update("DELETE FROM order_lines");
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("DELETE FROM cart_items");
        jdbcTemplate.update("DELETE FROM carts");
        jdbcTemplate.update("UPDATE products SET stock = CASE id WHEN 1 THEN 3 WHEN 2 THEN 20 WHEN 3 THEN 50 WHEN 4 THEN 40 WHEN 5 THEN 100 END");
        reset(couponRepository);
    }

    @Test
    void onlyOneConcurrentCheckoutCanRedeemCoupon() throws Exception {
        long firstProductId = productRepository.findById(2L).orElseThrow().getId();
        long secondProductId = productRepository.findById(3L).orElseThrow().getId();
        int firstStockBefore = productRepository.findById(firstProductId).orElseThrow().getStock();
        int secondStockBefore = productRepository.findById(secondProductId).orElseThrow().getStock();
        Cart firstCart = createCartWithProduct(firstProductId);
        Cart secondCart = createCartWithProduct(secondProductId);
        Coupon coupon = couponRepository.saveAndFlush(new Coupon("RACEREDM0001", 1, BigDecimal.TEN, CouponStatus.AVAILABLE));
        CyclicBarrier redemptionBarrier = new CyclicBarrier(2);
        doAnswer(invocation -> {
            String code = invocation.getArgument(0);
            UUID orderId = invocation.getArgument(1);
            redemptionBarrier.await(10, TimeUnit.SECONDS);
            return jdbcTemplate.update(
                    "UPDATE coupons SET status = ?, redeemed_order_id = ? WHERE code = ? AND status = ?",
                    CouponStatus.REDEEMED.name(), orderId, code, CouponStatus.AVAILABLE.name());
        }).when(couponRepository).redeemIfAvailable(any(String.class), any(UUID.class),
                eq(CouponStatus.AVAILABLE), eq(CouponStatus.REDEEMED));

        List<String> results;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<String> first = executor.submit(() -> checkoutResult(firstCart, coupon.getCode(), "coupon-race-a"));
            Future<String> second = executor.submit(() -> checkoutResult(secondCart, coupon.getCode(), "coupon-race-b"));
            results = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        } finally {
            reset(couponRepository);
        }

        assertThat(results).containsExactlyInAnyOrder("SUCCESS", "COUPON_ALREADY_REDEEMED");
        assertThat(orderRepository.count()).isEqualTo(1);
        assertThat(couponRepository.findByCode(coupon.getCode()).orElseThrow().getStatus()).isEqualTo(CouponStatus.REDEEMED);
        assertThat(couponRepository.findByCode(coupon.getCode()).orElseThrow().getRedeemedOrderId())
                .isEqualTo(orderRepository.findAll().getFirst().getId());
        assertThat(productRepository.findById(firstProductId).orElseThrow().getStock())
            .isIn(firstStockBefore - 1, firstStockBefore);
        assertThat(productRepository.findById(secondProductId).orElseThrow().getStock())
            .isIn(secondStockBefore - 1, secondStockBefore);
        assertThat((productRepository.findById(firstProductId).orElseThrow().getStock() == firstStockBefore - 1)
            ^ (productRepository.findById(secondProductId).orElseThrow().getStock() == secondStockBefore - 1)).isTrue();
        assertThat((cartService.get(firstCart.getId()).getStatus() == CartStatus.CHECKED_OUT)
                ^ (cartService.get(secondCart.getId()).getStatus() == CartStatus.CHECKED_OUT)).isTrue();
        assertThat(idempotencyRepository.count()).isEqualTo(1);
    }

    private Cart createCartWithProduct(long productId) {
        Cart cart = cartService.create();
        cartService.add(cart.getId(), productId, 1);
        return cart;
    }

    private String checkoutResult(Cart cart, String code, String key) {
        try {
            checkoutFacade.checkout(cart.getId(), key, code, null);
            return "SUCCESS";
        } catch (ApiException exception) {
            return exception.code;
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }
}