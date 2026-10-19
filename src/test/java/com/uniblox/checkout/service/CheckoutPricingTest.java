package com.uniblox.checkout.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CheckoutPricingTest {
    @Test
    void calculatesOrderLevelDiscountWithHalfUpRounding() {
        var totals = CheckoutPricing.calculate(new BigDecimal("0.05"), new BigDecimal("10"));

        assertThat(totals.gross()).isEqualByComparingTo("0.05");
        assertThat(totals.discount()).isEqualByComparingTo("0.01");
        assertThat(totals.net()).isEqualByComparingTo("0.04");
    }

    @Test
    void fullDiscountCannotMakeNetNegative() {
        var totals = CheckoutPricing.calculate(new BigDecimal("12.34"), new BigDecimal("100"));

        assertThat(totals.discount()).isEqualByComparingTo("12.34");
        assertThat(totals.net()).isEqualByComparingTo("0.00");
    }

    @Test
    void absentDiscountProducesZeroDiscount() {
        var totals = CheckoutPricing.calculate(new BigDecimal("0.01"), null);

        assertThat(totals.discount()).isEqualByComparingTo("0.00");
        assertThat(totals.net()).isEqualByComparingTo("0.01");
    }

    @Test
    void grossUsesScaledLineTotals() {
        BigDecimal gross = CheckoutPricing.gross(List.of(
                new CheckoutPricing.Line(new BigDecimal("0.10"), 3),
                new CheckoutPricing.Line(new BigDecimal("1.25"), 2)));

        assertThat(gross).isEqualByComparingTo("2.80");
    }
}