package com.uniblox.checkout.service;

import com.uniblox.checkout.api.ApiException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

public record CanonicalCheckoutRequest(UUID cartId, String idempotencyKey, String couponCode, BigDecimal expectedTotal) {
    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    public static CanonicalCheckoutRequest of(UUID cartId, String key, String coupon, BigDecimal expected) {
        if (cartId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "cartId is required");
        }
        if (key == null || key.isBlank() || !KEY.matcher(key).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Invalid Idempotency-Key");
        }
        if (coupon != null) {
            coupon = coupon.trim().toUpperCase(Locale.ROOT);
            if (coupon.isEmpty()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "couponCode must not be blank");
            }
        }
        if (expected != null) {
            if (expected.scale() > 2) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "expectedTotal must have at most two decimal places");
            }
            expected = expected.setScale(2, RoundingMode.HALF_UP);
        }
        return new CanonicalCheckoutRequest(cartId, key, coupon, expected);
    }
}
