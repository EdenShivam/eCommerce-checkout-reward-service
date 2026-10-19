package com.uniblox.checkout.service;

import com.uniblox.checkout.api.ApiException;
import com.uniblox.checkout.domain.Cart;
import com.uniblox.checkout.repository.CouponRepository;
import com.uniblox.checkout.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:coupon-generation-concurrency-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH")
@Import(CouponGenerationConcurrencyTest.BarrierGeneratorConfiguration.class)
class CouponGenerationConcurrencyTest {
    @Autowired private CouponAdminFacade couponAdminFacade;
    @Autowired private CouponRepository couponRepository;
    @Autowired private CartService cartService;
    @Autowired private CheckoutFacade checkoutFacade;
    @Autowired private ProductRepository productRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private BarrierCouponCodeGenerator codeGenerator;

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.update("DELETE FROM idempotency_records");
        jdbcTemplate.update("DELETE FROM coupons");
        jdbcTemplate.update("DELETE FROM order_lines");
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("DELETE FROM cart_items");
        jdbcTemplate.update("DELETE FROM carts");
        jdbcTemplate.update("UPDATE products SET stock = CASE id WHEN 1 THEN 3 WHEN 2 THEN 20 WHEN 3 THEN 50 WHEN 4 THEN 40 WHEN 5 THEN 100 END");
    }

    @Test
    void competingAdminRequestsCreateOnlyOneCouponForMilestone() throws Exception {
        for (int count = 0; count < 5; count++) {
            Cart cart = cartService.create();
            long productId = productRepository.findById(2L).orElseThrow().getId();
            cartService.add(cart.getId(), productId, 1);
            checkoutFacade.checkout(cart.getId(), "admin-race-" + UUID.randomUUID(), null, null);
        }
        codeGenerator.armBarrier();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<String> first = executor.submit(this::generateResult);
            Future<String> second = executor.submit(this::generateResult);
            List<String> outcomes = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));

            assertThat(outcomes).containsExactlyInAnyOrder("CREATED", "NO_ELIGIBLE_MILESTONE");
        }

        assertThat(couponRepository.count()).isEqualTo(1);
        assertThat(couponRepository.findByMilestone(1)).isPresent();
    }

    private String generateResult() {
        try {
            couponAdminFacade.generateNext();
            return "CREATED";
        } catch (ApiException exception) {
            return exception.code;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class BarrierGeneratorConfiguration {
        @Bean
        @Primary
        BarrierCouponCodeGenerator barrierCouponCodeGenerator() {
            return new BarrierCouponCodeGenerator();
        }
    }

    static class BarrierCouponCodeGenerator implements CouponCodeGenerator {
        private volatile CyclicBarrier barrier;

        void armBarrier() {
            barrier = new CyclicBarrier(2);
        }

        @Override
        public String nextCode() {
            CyclicBarrier current = barrier;
            if (current != null) {
                try {
                    current.await(10, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new AssertionError("Coupon generation attempts did not overlap", exception);
                } finally {
                    if (current.getNumberWaiting() == 0) {
                        barrier = null;
                    }
                }
            }
            return UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        }
    }
}