package com.uniblox.checkout.service;

import com.uniblox.checkout.domain.Cart;
import com.uniblox.checkout.domain.Coupon;
import com.uniblox.checkout.domain.Product;
import com.uniblox.checkout.api.ApiException;
import com.uniblox.checkout.repository.CouponRepository;
import com.uniblox.checkout.repository.IdempotencyRecordRepository;
import com.uniblox.checkout.repository.OrderRepository;
import com.uniblox.checkout.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:phase4-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH")
class Phase4IntegrationTest {
    @Autowired private CartService cartService;
    @Autowired private CheckoutFacade checkoutFacade;
    @Autowired private CouponAdminFacade couponAdminFacade;
    @Autowired private ReportService reportService;
    @Autowired private ProductRepository productRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private CouponRepository couponRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoSpyBean private IdempotencyRecordRepository idempotencyRecords;

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
    void reportReconcilesPersistedOrderLinesAndListsEveryProduct() {
        var before = reportService.getReport();
        Product product = highestStockProduct();
        checkout(product, 2);

        var after = reportService.getReport();
        var purchased = after.purchasedQuantityByProduct().stream().toList();

        assertThat(purchased).hasSize(productRepository.findAll().size());
        assertThat(purchased).extracting(ReportService.PurchasedQuantity::productId)
                .isSortedAccordingTo(Comparator.naturalOrder());
        assertThat(quantityFor(after, product.getId()) - quantityFor(before, product.getId())).isEqualTo(2);
        assertThat(new BigDecimal(after.gross()).subtract(new BigDecimal(after.totalDiscounts())))
                .isEqualByComparingTo(after.net());
        assertThat(after.totalOrders()).isEqualTo(before.totalOrders() + 1);
        assertThat(after.couponsAvailable() + after.couponsRedeemed()).isEqualTo(after.couponsGenerated());
        assertThat(after.purchasedQuantityByProduct().stream().mapToLong(ReportService.PurchasedQuantity::purchasedQuantity).sum())
                .isEqualTo(orderRepository.findAll().stream().flatMap(order -> order.getLines().stream()).mapToLong(line -> line.getQuantity()).sum());
    }

    @Test
    void couponGenerationCreatesLowestEligibleUnrewardedMilestone() {
        Set<Integer> existing = couponRepository.findAll().stream().map(Coupon::getMilestone).collect(Collectors.toSet());
        int milestone = 1;
        while (existing.contains(milestone)) {
            milestone++;
        }
        while (orderRepository.count() < (long) milestone * 5) {
            checkout(highestStockProduct(), 1);
        }

        Coupon coupon = couponAdminFacade.generateNext();

        assertThat(coupon.getMilestone()).isEqualTo(milestone);
        assertThat(coupon.getStatus().name()).isEqualTo("AVAILABLE");
        assertThat(couponRepository.findByMilestone(milestone).orElseThrow().getCode()).isEqualTo(coupon.getCode());
    }

    @Test
    void competingCheckoutsCannotOversellAvailableStock() throws Exception {
        Product product = highestStockProduct();
        int available = product.getStock();
        assertThat(available).isPositive();
        Cart firstCart = cartService.create();
        Cart secondCart = cartService.create();
        cartService.add(firstCart.getId(), product.getId(), available);
        cartService.add(secondCart.getId(), product.getId(), available);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(() -> checkoutAtBarrier(firstCart, ready, start));
            Future<Boolean> second = executor.submit(() -> checkoutAtBarrier(secondCart, ready, start));
            ready.await();
            start.countDown();

            assertThat((first.get() ? 1 : 0) + (second.get() ? 1 : 0)).isEqualTo(1);
        }

        assertThat(productRepository.findById(product.getId()).orElseThrow().getStock()).isZero();
        assertThat(orderRepository.findByCartId(firstCart.getId()).isPresent()
                ^ orderRepository.findByCartId(secondCart.getId()).isPresent()).isTrue();
    }

    @Test
    void reportSnapshotRemainsReconciledWhileCheckoutTransactionsAreUncommitted() throws Exception {
        Product productA = productRepository.findById(2L).orElseThrow();
        Product productB = productRepository.findById(3L).orElseThrow();
        BigDecimal perOrderGross = productA.getUnitPrice().multiply(BigDecimal.valueOf(2))
                .add(productB.getUnitPrice()).setScale(2, RoundingMode.HALF_UP);
        int iterations = 5;
        List<WriterCheckpoint> checkpoints = java.util.stream.IntStream.range(0, iterations)
                .mapToObj(ignored -> new WriterCheckpoint()).toList();
        AtomicInteger checkpointIndex = new AtomicInteger();
        Set<Long> observedOrderCounts = new ConcurrentSkipListSet<>();

        doAnswer(invocation -> {
            Long recordId = invocation.getArgument(0);
            UUID orderId = invocation.getArgument(1);
            int updated = jdbcTemplate.update("UPDATE idempotency_records SET order_id = ? WHERE id = ?", orderId, recordId);
            WriterCheckpoint checkpoint = checkpoints.get(checkpointIndex.getAndIncrement());
            checkpoint.writerPaused.countDown();
            if (!checkpoint.allowCommit.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Reader did not inspect the in-flight checkout transaction");
            }
            return updated;
        }).when(idempotencyRecords).completeOrder(any(Long.class), any(UUID.class));

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> writer = executor.submit(() -> {
                for (int iteration = 1; iteration <= iterations; iteration++) {
                    Cart cart = cartService.create();
                    cartService.add(cart.getId(), productA.getId(), 2);
                    cartService.add(cart.getId(), productB.getId(), 1);
                    checkoutFacade.checkout(cart.getId(), "snapshot-writer-" + iteration, null, null);
                }
            });
            try {
                for (int iteration = 1; iteration <= iterations; iteration++) {
                    WriterCheckpoint checkpoint = checkpoints.get(iteration - 1);
                    assertThat(checkpoint.writerPaused.await(10, TimeUnit.SECONDS))
                            .as("writer transaction %s should be paused before commit", iteration)
                            .isTrue();

                    var report = reportService.getReport();
                    observedOrderCounts.add(report.totalOrders());
                    long committedOrders = iteration - 1L;
                    assertThat(report.totalOrders()).isEqualTo(committedOrders);
                    assertThat(quantityFor(report, productA.getId())).isEqualTo(committedOrders * 2);
                    assertThat(quantityFor(report, productB.getId())).isEqualTo(committedOrders);
                    assertThat(new BigDecimal(report.gross())).isEqualByComparingTo(perOrderGross.multiply(BigDecimal.valueOf(committedOrders)));
                    assertThat(new BigDecimal(report.gross()).subtract(new BigDecimal(report.totalDiscounts())))
                            .isEqualByComparingTo(report.net());
                    assertThat(report.purchasedQuantityByProduct().stream()
                            .mapToLong(ReportService.PurchasedQuantity::purchasedQuantity).sum()).isEqualTo(committedOrders * 3);
                    assertThat(report.couponsGenerated()).isZero();
                    assertThat(report.couponsAvailable()).isZero();
                    assertThat(report.couponsRedeemed()).isZero();
                    assertThat(report.purchasedQuantityByProduct()).hasSize(productRepository.findAll().size());
                    checkpoint.allowCommit.countDown();
                }
            } finally {
                checkpoints.forEach(checkpoint -> checkpoint.allowCommit.countDown());
            }
            writer.get(20, TimeUnit.SECONDS);
        } finally {
            reset(idempotencyRecords);
        }

        assertThat(observedOrderCounts).as("inconclusive: report did not observe several overlapping in-flight writer transactions")
                .containsExactly(0L, 1L, 2L, 3L, 4L);
        assertThat(reportService.getReport().totalOrders()).isEqualTo(iterations);
    }

    private boolean checkoutAtBarrier(Cart cart, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        try {
            checkoutFacade.checkout(cart.getId(), "phase4-race-" + cart.getId(), null, null);
            return true;
        } catch (ApiException expectedConflict) {
            return false;
        }
    }

    private Product highestStockProduct() {
        return productRepository.findAll().stream().max(Comparator.comparingInt(Product::getStock)).orElseThrow();
    }

    private void checkout(Product product, int quantity) {
        Cart cart = cartService.create();
        cartService.add(cart.getId(), product.getId(), quantity);
        checkoutFacade.checkout(cart.getId(), "phase4-" + UUID.randomUUID(), null, null);
    }

    private int quantityFor(ReportService.ReportResponse report, Long productId) {
        return report.purchasedQuantityByProduct().stream()
                .filter(purchased -> purchased.productId().equals(productId))
                .findFirst().orElseThrow().purchasedQuantity();
    }

    private static final class WriterCheckpoint {
        private final CountDownLatch writerPaused = new CountDownLatch(1);
        private final CountDownLatch allowCommit = new CountDownLatch(1);
    }
}