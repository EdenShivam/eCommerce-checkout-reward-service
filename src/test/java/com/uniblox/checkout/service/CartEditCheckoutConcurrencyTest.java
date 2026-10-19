package com.uniblox.checkout.service;

import com.uniblox.checkout.api.ApiException;
import com.uniblox.checkout.domain.Cart;
import com.uniblox.checkout.domain.CartStatus;
import com.uniblox.checkout.domain.Order;
import com.uniblox.checkout.repository.OrderRepository;
import com.uniblox.checkout.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:cart-edit-checkout-race-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH")
class CartEditCheckoutConcurrencyTest {
    @Autowired private CartService cartService;
    @Autowired private CheckoutFacade checkoutFacade;
    @Autowired private OrderRepository orderRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

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
    void racingCartEditIsEitherIncludedOrRejectedWithoutDivergentOrderLines() throws Exception {
        long initialProductId = productRepository.findById(2L).orElseThrow().getId();
        long lateProductId = productRepository.findById(3L).orElseThrow().getId();
        Cart cart = cartService.create();
        cartService.add(cart.getId(), initialProductId, 1);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Order> checkout = executor.submit(() -> {
                ready.countDown();
                start.await();
                return checkoutFacade.checkout(cart.getId(), "cart-edit-race-" + cart.getId(), null, null);
            });
            Future<String> edit = executor.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    cartService.add(cart.getId(), lateProductId, 1);
                    return "INCLUDED";
                } catch (ApiException exception) {
                    if (!"CART_ALREADY_CHECKED_OUT".equals(exception.code)) {
                        throw exception;
                    }
                    return "REJECTED";
                }
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            Order order = checkout.get(15, TimeUnit.SECONDS);
            String editOutcome = edit.get(15, TimeUnit.SECONDS);
            var finalCart = cartService.get(cart.getId());
            Map<Long, Integer> cartLines = finalCart.getItems().stream()
                    .collect(Collectors.toMap(item -> item.getProductId(), item -> item.getQuantity()));
            Map<Long, Integer> orderLines = order.getLines().stream()
                    .collect(Collectors.toMap(line -> line.getProductId(), line -> line.getQuantity()));

            assertThat(finalCart.getStatus()).isEqualTo(CartStatus.CHECKED_OUT);
            assertThat(editOutcome).isIn("INCLUDED", "REJECTED");
            assertThat(orderLines).isEqualTo(cartLines);
            assertThat(orderRepository.findByCartId(cart.getId())).get().extracting(Order::getId).isEqualTo(order.getId());
            assertThat(cartLines.containsKey(lateProductId)).isEqualTo("INCLUDED".equals(editOutcome));
        }
    }
}