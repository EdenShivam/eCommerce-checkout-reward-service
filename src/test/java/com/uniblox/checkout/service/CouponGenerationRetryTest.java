package com.uniblox.checkout.service;

import com.uniblox.checkout.domain.Cart;
import com.uniblox.checkout.domain.Coupon;
import com.uniblox.checkout.domain.CouponStatus;
import com.uniblox.checkout.api.ApiException;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:coupon-retry-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH")
@Import(CouponGenerationRetryTest.GeneratorTestConfiguration.class)
class CouponGenerationRetryTest {
    @Autowired private CouponAdminFacade couponAdminFacade;
    @Autowired private CouponRepository couponRepository;
    @Autowired private CartService cartService;
    @Autowired private CheckoutFacade checkoutFacade;
    @Autowired private ProductRepository productRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private SequencedCouponCodeGenerator generator;

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.update("DELETE FROM idempotency_records");
        jdbcTemplate.update("DELETE FROM coupons");
        jdbcTemplate.update("DELETE FROM order_lines");
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("DELETE FROM cart_items");
        jdbcTemplate.update("DELETE FROM carts");
        jdbcTemplate.update("UPDATE products SET stock = CASE id WHEN 1 THEN 3 WHEN 2 THEN 20 WHEN 3 THEN 50 WHEN 4 THEN 40 WHEN 5 THEN 100 END");
        generator.reset();
    }

    @Test
    void namedCouponCodeCollisionRetriesWithFreshCodeInFreshTransaction() {
        couponRepository.saveAndFlush(new Coupon("COLLISION0001", 99, BigDecimal.TEN, CouponStatus.AVAILABLE));
        for (int count = 0; count < 5; count++) {
            Cart cart = cartService.create();
            long productId = productRepository.findById(2L).orElseThrow().getId();
            cartService.add(cart.getId(), productId, 1);
            checkoutFacade.checkout(cart.getId(), "coupon-retry-" + UUID.randomUUID(), null, null);
        }
        generator.enqueue("COLLISION0001", "FRESHCODE0001");

        Coupon generated = couponAdminFacade.generateNext();

        assertThat(generated.getCode()).isEqualTo("FRESHCODE0001");
        assertThat(generated.getMilestone()).isEqualTo(1);
        assertThat(couponRepository.count()).isEqualTo(2);
        assertThat(generator.transactionResources()).hasSize(2);
        assertThat(generator.transactionResources().get(0)).isNotSameAs(generator.transactionResources().get(1));
    }

    @Test
    void exhaustingCodeCollisionBudgetLeavesNoPartialCoupon() {
        couponRepository.saveAndFlush(new Coupon("COLLISION0001", 99, BigDecimal.TEN, CouponStatus.AVAILABLE));
        for (int count = 0; count < 5; count++) {
            Cart cart = cartService.create();
            long productId = productRepository.findById(2L).orElseThrow().getId();
            cartService.add(cart.getId(), productId, 1);
            checkoutFacade.checkout(cart.getId(), "coupon-exhaust-" + UUID.randomUUID(), null, null);
        }
        generator.enqueue("COLLISION0001", "COLLISION0001", "COLLISION0001", "COLLISION0001", "COLLISION0001");

        assertThatThrownBy(couponAdminFacade::generateNext)
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("RETRYABLE_CONFLICT");

        assertThat(couponRepository.count()).isEqualTo(1);
        assertThat(generator.transactionResources()).hasSize(5);
        assertThat(generator.transactionResources()).doesNotHaveDuplicates();
    }

    @Test
    void milestoneConflictDoesNotConsumeCodeCollisionBudget() {
        couponRepository.saveAndFlush(new Coupon("COLLISION0001", 99, BigDecimal.TEN, CouponStatus.AVAILABLE));
        for (int count = 0; count < 10; count++) {
            Cart cart = cartService.create();
            long productId = productRepository.findById(2L).orElseThrow().getId();
            cartService.add(cart.getId(), productId, 1);
            checkoutFacade.checkout(cart.getId(), "separate-budget-" + UUID.randomUUID(), null, null);
        }
        generator.enqueueCompetitorAtNextCode();
        generator.enqueue("RACECODE0001", "COLLISION0001", "FINALCODE00001");

        Coupon generated = couponAdminFacade.generateNext();

        assertThat(generated.getMilestone()).isEqualTo(2);
        assertThat(generated.getCode()).isEqualTo("FINALCODE00001");
        assertThat(couponRepository.findByMilestone(1)).isPresent();
        assertThat(couponRepository.findByMilestone(2)).get().extracting(Coupon::getCode).isEqualTo("FINALCODE00001");
        assertThat(couponRepository.count()).isEqualTo(3);
        assertThat(generator.transactionResources()).hasSize(3);
        assertThat(generator.transactionResources()).doesNotHaveDuplicates();
    }

    @Test
    void exhaustingMilestoneBudgetLeavesOnlyCommittedCompetitorCoupons() {
        couponRepository.saveAndFlush(new Coupon("HIGHMILESTONE", 99, BigDecimal.TEN, CouponStatus.AVAILABLE));
        for (int count = 0; count < 15; count++) {
            Cart cart = cartService.create();
            long productId = productRepository.findById(2L).orElseThrow().getId();
            cartService.add(cart.getId(), productId, 1);
            checkoutFacade.checkout(cart.getId(), "milestone-exhaust-" + UUID.randomUUID(), null, null);
        }
        generator.enqueueCompetitorMilestones(1, 2, 3);
        generator.enqueue("BUDGET000001", "BUDGET000002", "BUDGET000003");

        assertThatThrownBy(couponAdminFacade::generateNext)
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("RETRYABLE_CONFLICT");

        assertThat(couponRepository.count()).isEqualTo(4);
        assertThat(couponRepository.findByMilestone(1)).isPresent();
        assertThat(couponRepository.findByMilestone(2)).isPresent();
        assertThat(couponRepository.findByMilestone(3)).isPresent();
        assertThat(generator.transactionResources()).hasSize(3);
        assertThat(generator.transactionResources()).doesNotHaveDuplicates();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class GeneratorTestConfiguration {
        @Bean
        @Primary
        SequencedCouponCodeGenerator sequencedCouponCodeGenerator(DataSource dataSource,
                                                                    JdbcTemplate jdbcTemplate,
                                                                    PlatformTransactionManager transactionManager) {
            return new SequencedCouponCodeGenerator(dataSource, jdbcTemplate, transactionManager);
        }
    }

    static class SequencedCouponCodeGenerator implements CouponCodeGenerator {
        private final DataSource dataSource;
        private final JdbcTemplate jdbcTemplate;
        private final TransactionTemplate requiresNew;
        private final Queue<String> codes = new ConcurrentLinkedQueue<>();
        private final Queue<Integer> competingMilestones = new ConcurrentLinkedQueue<>();
        private final List<Object> transactionResources = new ArrayList<>();

        SequencedCouponCodeGenerator(DataSource dataSource, JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
            this.dataSource = dataSource;
            this.jdbcTemplate = jdbcTemplate;
            this.requiresNew = new TransactionTemplate(transactionManager);
            this.requiresNew.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        }

        @Override
        public synchronized String nextCode() {
            transactionResources.add(TransactionSynchronizationManager.getResource(dataSource));
            Integer competingMilestone = competingMilestones.poll();
            if (competingMilestone != null) {
                requiresNew.executeWithoutResult(status -> jdbcTemplate.update(
                        "INSERT INTO coupons (code, milestone, discount_percent, status, redeemed_order_id, created_at) VALUES (?, ?, ?, ?, NULL, CURRENT_TIMESTAMP)",
                        "COMPETING" + String.format("%04d", competingMilestone), competingMilestone,
                        BigDecimal.TEN, CouponStatus.AVAILABLE.name()));
            }
            return codes.remove();
        }

        synchronized void enqueue(String... values) {
            codes.addAll(List.of(values));
        }

        synchronized void enqueueCompetitorAtNextCode() {
            competingMilestones.add(1);
        }

        synchronized void enqueueCompetitorMilestones(int... milestones) {
            for (int milestone : milestones) {
                competingMilestones.add(milestone);
            }
        }

        synchronized void reset() {
            codes.clear();
            competingMilestones.clear();
            transactionResources.clear();
        }

        synchronized List<Object> transactionResources() {
            return List.copyOf(transactionResources);
        }
    }
}