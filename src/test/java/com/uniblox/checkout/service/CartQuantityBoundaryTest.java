package com.uniblox.checkout.service;

import com.uniblox.checkout.api.ApiException;
import com.uniblox.checkout.domain.Cart;
import com.uniblox.checkout.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:cart-quantity-boundary-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH")
class CartQuantityBoundaryTest {
    @Autowired private CartService cartService;
    @Autowired private ProductRepository productRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.update("DELETE FROM cart_items");
        jdbcTemplate.update("DELETE FROM carts");
        jdbcTemplate.update("UPDATE products SET stock = CASE id WHEN 1 THEN 3 WHEN 2 THEN 20 WHEN 3 THEN 50 WHEN 4 THEN 40 WHEN 5 THEN 100 END");
    }

    @Test
    void addMergeValidatesResultingQuantityAndPreservesExistingLine() {
        Cart cart = cartService.create();
        long productId = productRepository.findById(1L).orElseThrow().getId();
        cartService.add(cart.getId(), productId, 2);

        assertThatThrownBy(() -> cartService.add(cart.getId(), productId, 2))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("INSUFFICIENT_STOCK");

        assertThat(cartService.get(cart.getId()).getItems()).singleElement()
                .satisfies(item -> assertThat(item.getQuantity()).isEqualTo(2));
    }

    @Test
    void integerOverflowDuringMergeIsValidationFailureAndDoesNotMutateLine() {
        Cart cart = cartService.create();
        long productId = productRepository.findById(2L).orElseThrow().getId();
        cartService.add(cart.getId(), productId, 1);
        jdbcTemplate.update("UPDATE cart_items SET quantity = ? WHERE cart_id = ? AND product_id = ?",
                Integer.MAX_VALUE, cart.getId(), productId);

        assertThatThrownBy(() -> cartService.add(cart.getId(), productId, 1))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("VALIDATION_FAILED");

        assertThat(jdbcTemplate.queryForObject("SELECT quantity FROM cart_items WHERE cart_id = ? AND product_id = ?",
                Integer.class, cart.getId(), productId)).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void maximumPerRequestQuantityIsEnforced() {
        Cart cart = cartService.create();
        long productId = productRepository.findById(2L).orElseThrow().getId();

        assertThatThrownBy(() -> cartService.add(cart.getId(), productId, 1_000_001))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("VALIDATION_FAILED");
        assertThat(cartService.get(cart.getId()).getItems()).isEmpty();
    }
}