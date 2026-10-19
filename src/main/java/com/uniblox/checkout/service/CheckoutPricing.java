package com.uniblox.checkout.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public final class CheckoutPricing {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private CheckoutPricing() {
    }

    public static BigDecimal gross(List<Line> lines) {
        return lines.stream()
                .map(line -> line.unitPrice().multiply(BigDecimal.valueOf(line.quantity())).setScale(2, RoundingMode.HALF_UP))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    public static Amounts calculate(BigDecimal gross, BigDecimal discountPercent) {
        BigDecimal normalizedGross = gross.setScale(2, RoundingMode.HALF_UP);
        BigDecimal discount = ZERO_MONEY;
        if (discountPercent != null) {
            discount = normalizedGross.multiply(discountPercent)
                    .divide(HUNDRED, 2, RoundingMode.HALF_UP);
            if (discount.signum() < 0) {
                discount = ZERO_MONEY;
            }
            if (discount.compareTo(normalizedGross) > 0) {
                discount = normalizedGross;
            }
        }
        BigDecimal net = normalizedGross.subtract(discount).setScale(2, RoundingMode.HALF_UP);
        return new Amounts(normalizedGross, discount, net);
    }

    public record Line(BigDecimal unitPrice, int quantity) {
    }

    public record Amounts(BigDecimal gross, BigDecimal discount, BigDecimal net) {
    }
}