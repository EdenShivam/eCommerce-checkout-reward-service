package com.uniblox.checkout.config;

import com.uniblox.checkout.domain.Cart;
import com.uniblox.checkout.domain.Coupon;
import com.uniblox.checkout.domain.CouponStatus;
import com.uniblox.checkout.repository.CouponRepository;
import com.uniblox.checkout.service.CartService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:database-constraint-findings-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH")
class DatabaseConstraintFindingsTest {
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private CartService cartService;
    @Autowired private CouponRepository couponRepository;

    @BeforeEach
    void clearState() {
        jdbcTemplate.update("DELETE FROM idempotency_records");
        jdbcTemplate.update("DELETE FROM coupons");
        jdbcTemplate.update("DELETE FROM order_lines");
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("DELETE FROM cart_items");
        jdbcTemplate.update("DELETE FROM carts");
        jdbcTemplate.update("UPDATE products SET stock = CASE id WHEN 1 THEN 3 WHEN 2 THEN 20 WHEN 3 THEN 50 WHEN 4 THEN 40 WHEN 5 THEN 100 END");
    }

    @Test
    void h2ReportsNamedUniqueForeignKeyAndCheckConstraints() {
        Cart cart = cartService.create();

        assertConstraint("uk_idempotency_cart_key", "23505", () -> {
            insertIdempotency(cart.getId(), "same-key");
            insertIdempotency(cart.getId(), "same-key");
        });

        assertConstraint("uk_order_cart_id", "23505", () -> {
            insertOrder(UUID.randomUUID(), cart.getId());
            insertOrder(UUID.randomUUID(), cart.getId());
        });

        couponRepository.saveAndFlush(new Coupon("CODEFINDING01", 10, BigDecimal.TEN, CouponStatus.AVAILABLE));
        assertConstraint("uk_coupon_milestone", "23505", () -> couponRepository.saveAndFlush(
                new Coupon("CODEFINDING02", 10, BigDecimal.TEN, CouponStatus.AVAILABLE)));
        assertConstraint("uk_coupon_code", "23505", () -> couponRepository.saveAndFlush(
                new Coupon("CODEFINDING01", 11, BigDecimal.TEN, CouponStatus.AVAILABLE)));

        assertConstraint("fk_cart_item_cart", "23506", () -> jdbcTemplate.update(
                "INSERT INTO cart_items (cart_id, product_id, quantity) VALUES (?, ?, ?)", UUID.randomUUID(), 1L, 1));
        assertConstraint("ck_product_stock_non_negative", "23513", () -> jdbcTemplate.update(
                "UPDATE products SET stock = -1 WHERE id = 1"));
    }

    private void insertIdempotency(UUID cartId, String key) {
        jdbcTemplate.update("INSERT INTO idempotency_records (cart_id, idempotency_key, request_hash, order_id, created_at) VALUES (?, ?, ?, NULL, CURRENT_TIMESTAMP)",
                cartId, key, "0".repeat(64));
    }

    private void insertOrder(UUID orderId, UUID cartId) {
        jdbcTemplate.update("INSERT INTO orders (id, cart_id, status, gross, discount, net, coupon_code, discount_percent, created_at) VALUES (?, ?, 'PLACED', 1.00, 0.00, 1.00, NULL, NULL, CURRENT_TIMESTAMP)",
                orderId, cartId);
    }

    private void assertConstraint(String name, String sqlState, Runnable statement) {
        assertThatThrownBy(statement::run)
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(exception -> {
                    SQLException sqlException = findSqlException(exception);
                    assertThat((Object) sqlException).isNotNull();
                    assertThat(sqlException.getSQLState()).isEqualTo(sqlState);
                    assertThat(exception.getMessage().toLowerCase()).contains(name.toLowerCase());
                });
    }

    private SQLException findSqlException(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                return sqlException;
            }
        }
        return null;
    }
}