package com.uniblox.checkout.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:checkout-api-contract-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH")
@AutoConfigureMockMvc
class CheckoutApiContractTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
        @Autowired private ObjectMapper objectMapper;

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.update("DELETE FROM idempotency_records");
        jdbcTemplate.update("DELETE FROM coupons");
        jdbcTemplate.update("DELETE FROM order_lines");
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("DELETE FROM cart_items");
        jdbcTemplate.update("DELETE FROM carts");
        jdbcTemplate.update("UPDATE products SET stock = CASE id WHEN 1 THEN 3 WHEN 2 THEN 20 WHEN 3 THEN 50 WHEN 4 THEN 40 WHEN 5 THEN 100 END");
        jdbcTemplate.update("UPDATE products SET unit_price = CASE id WHEN 1 THEN 18.50 WHEN 2 THEN 129.99 WHEN 3 THEN 49.50 WHEN 4 THEN 89.00 WHEN 5 THEN 39.95 END");
    }

    @Test
    void firstCheckoutAndReplayHaveDistinctHttpContractAndStringMoney() throws Exception {
        String cartId = mockMvc.perform(post("/carts"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(cartId).get("id").asText();
        mockMvc.perform(post("/carts/{id}/items", id)
                        .contentType("application/json")
                        .content("{\"productId\":2,\"quantity\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtotal").value("129.99"));

        mockMvc.perform(post("/carts/{id}/checkout", id).header("Idempotency-Key", "api-contract-key"))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.gross").value("129.99"))
                .andExpect(jsonPath("$.lines[0].unitPrice").value("129.99"));

        mockMvc.perform(post("/carts/{id}/checkout", id).header("Idempotency-Key", "api-contract-key"))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.gross").value("129.99"));
    }

    @Test
    void missingIdempotencyKeyUsesStableValidationProblem() throws Exception {
        String cartId = mockMvc.perform(post("/carts"))
                .andReturn().getResponse().getContentAsString();
        String id = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(cartId).get("id").asText();

        mockMvc.perform(post("/carts/{id}/checkout", id))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.Idempotency-Key").exists());
    }

    @Test
    void malformedJsonUsesValidationProblemInsteadOfInternalError() throws Exception {
        String cartId = objectMapper.readTree(mockMvc.perform(post("/carts"))
                .andReturn().getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(post("/carts/{id}/items", cartId)
                        .contentType("application/json")
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void checkoutMoneyAndCouponFieldsRequireJsonStrings() throws Exception {
        String cartId = objectMapper.readTree(mockMvc.perform(post("/carts"))
                .andReturn().getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(post("/carts/{id}/checkout", cartId)
                        .header("Idempotency-Key", "numeric-money")
                        .contentType("application/json")
                        .content("{\"expectedTotal\":1.00}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post("/carts/{id}/checkout", cartId)
                        .header("Idempotency-Key", "overprecision-money")
                        .contentType("application/json")
                        .content("{\"expectedTotal\":\"1.001\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post("/carts/{id}/checkout", cartId)
                        .header("Idempotency-Key", "numeric-coupon")
                        .contentType("application/json")
                        .content("{\"couponCode\":123}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void adminGenerationAndReportExposeStableContract() throws Exception {
        mockMvc.perform(post("/admin/coupons/generate"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_ELIGIBLE_MILESTONE"));

        JsonNode emptyReport = objectMapper.readTree(mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/admin/report"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(emptyReport.get("gross").asText()).isEqualTo("0.00");
        assertThat(emptyReport.get("purchasedQuantityByProduct")).hasSize(5);
        for (JsonNode product : emptyReport.get("purchasedQuantityByProduct")) {
            assertThat(product.get("purchasedQuantity").asInt()).isZero();
        }

        JsonNode products = objectMapper.readTree(mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/products"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        long purchasedProductId = products.get(1).get("id").asLong();
        String productName = products.get(1).get("name").asText();

        for (int index = 0; index < 5; index++) {
            JsonNode cart = objectMapper.readTree(mockMvc.perform(post("/carts"))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
            String cartId = cart.get("id").asText();
            mockMvc.perform(post("/carts/{id}/items", cartId)
                            .contentType("application/json")
                            .content("{\"productId\":" + purchasedProductId + ",\"quantity\":1}"))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/carts/{id}/checkout", cartId)
                            .header("Idempotency-Key", "admin-contract-" + index))
                    .andExpect(status().isCreated());
        }

        JsonNode coupon = objectMapper.readTree(mockMvc.perform(post("/admin/coupons/generate"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(coupon.get("milestone").asInt()).isEqualTo(1);
        assertThat(coupon.get("discountPercent").asText()).isEqualTo("10.00");
        assertThat(coupon.get("status").asText()).isEqualTo("AVAILABLE");

        JsonNode report = objectMapper.readTree(mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/admin/report"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(report.get("totalOrders").asLong()).isEqualTo(5);
        assertThat(report.get("gross").asText()).isEqualTo("649.95");
        assertThat(report.get("totalDiscounts").asText()).isEqualTo("0.00");
        assertThat(report.get("net").asText()).isEqualTo("649.95");
        assertThat(report.get("couponsGenerated").asLong()).isEqualTo(1);
        assertThat(report.get("couponsAvailable").asLong()).isEqualTo(1);
        assertThat(report.get("couponsRedeemed").asLong()).isZero();
        JsonNode purchased = report.get("purchasedQuantityByProduct");
        assertThat(purchased).hasSize(products.size());
        assertThat(purchased.get(1).get("productId").asLong()).isEqualTo(purchasedProductId);
        assertThat(purchased.get(1).get("productName").asText()).isEqualTo(productName);
        assertThat(purchased.get(1).get("purchasedQuantity").asInt()).isEqualTo(5);
        assertThat(purchased.get(0).get("purchasedQuantity").asInt()).isZero();

        JsonNode discountedCart = objectMapper.readTree(mockMvc.perform(post("/carts"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String discountedCartId = discountedCart.get("id").asText();
        mockMvc.perform(post("/carts/{id}/items", discountedCartId)
                        .contentType("application/json")
                        .content("{\"productId\":" + purchasedProductId + ",\"quantity\":1}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/carts/{id}/checkout", discountedCartId)
                        .header("Idempotency-Key", "admin-contract-redeem")
                        .contentType("application/json")
                        .content("{\"couponCode\":\"" + coupon.get("code").asText() + "\",\"expectedTotal\":\"116.99\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.gross").value("129.99"))
                .andExpect(jsonPath("$.discount").value("13.00"))
                .andExpect(jsonPath("$.net").value("116.99"));

        JsonNode redeemedReport = objectMapper.readTree(mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/admin/report"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(redeemedReport.get("totalOrders").asLong()).isEqualTo(6);
        assertThat(redeemedReport.get("gross").asText()).isEqualTo("779.94");
        assertThat(redeemedReport.get("totalDiscounts").asText()).isEqualTo("13.00");
        assertThat(redeemedReport.get("net").asText()).isEqualTo("766.94");
        assertThat(redeemedReport.get("couponsAvailable").asLong()).isZero();
        assertThat(redeemedReport.get("couponsRedeemed").asLong()).isEqualTo(1);
        assertThat(redeemedReport.get("purchasedQuantityByProduct").get(1).get("purchasedQuantity").asInt()).isEqualTo(6);

        String firstReport = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/admin/report"))
                .andReturn().getResponse().getContentAsString();
        String secondReport = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/admin/report"))
                .andReturn().getResponse().getContentAsString();
        assertThat(secondReport).isEqualTo(firstReport);
    }

    @Test
    void orderSnapshotSurvivesLaterCatalogPriceChange() throws Exception {
        JsonNode cart = objectMapper.readTree(mockMvc.perform(post("/carts"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String cartId = cart.get("id").asText();
        mockMvc.perform(post("/carts/{id}/items", cartId)
                        .contentType("application/json")
                        .content("{\"productId\":2,\"quantity\":1}"))
                .andExpect(status().isOk());
        JsonNode order = objectMapper.readTree(mockMvc.perform(post("/carts/{id}/checkout", cartId)
                        .header("Idempotency-Key", "price-snapshot"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        jdbcTemplate.update("UPDATE products SET unit_price = 999.99 WHERE id = 2");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/orders/{orderId}", order.get("id").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gross").value("129.99"))
                .andExpect(jsonPath("$.lines[0].unitPrice").value("129.99"));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/carts/{id}", cartId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].unitPrice").value("999.99"));
    }

    @Test
    void unknownCartAndCheckedOutCartMutationErrorsMatchContract() throws Exception {
        String unknownCartId = java.util.UUID.randomUUID().toString();
        mockMvc.perform(post("/carts/{id}/checkout", unknownCartId).header("Idempotency-Key", "unknown-cart"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CART_NOT_FOUND"));
        mockMvc.perform(post("/carts/{id}/items", unknownCartId)
                        .contentType("application/json")
                        .content("{\"productId\":2,\"quantity\":1}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CART_NOT_FOUND"));

        JsonNode cart = objectMapper.readTree(mockMvc.perform(post("/carts"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String cartId = cart.get("id").asText();
        mockMvc.perform(post("/carts/{id}/items", cartId)
                        .contentType("application/json")
                        .content("{\"productId\":2,\"quantity\":1}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/carts/{id}/checkout", cartId).header("Idempotency-Key", "closed-cart"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/carts/{id}/items", cartId)
                        .contentType("application/json")
                        .content("{\"productId\":2,\"quantity\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CART_ALREADY_CHECKED_OUT"));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/carts/{id}/items/{productId}", cartId, 2)
                        .contentType("application/json")
                        .content("{\"quantity\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CART_ALREADY_CHECKED_OUT"));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/carts/{id}/items/{productId}", cartId, 2))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CART_ALREADY_CHECKED_OUT"));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/carts/{id}", cartId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].quantity").value(1));
    }
}