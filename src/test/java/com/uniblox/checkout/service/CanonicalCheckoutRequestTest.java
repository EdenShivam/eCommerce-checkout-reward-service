package com.uniblox.checkout.service;

import com.uniblox.checkout.api.ApiException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CanonicalCheckoutRequestTest {
    private final CheckoutRequestHasher hasher = new CheckoutRequestHasher();
    private final UUID cartId = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");

    @Test
    void couponIsTrimmedAndUppercasedWithLocaleRoot() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            CanonicalCheckoutRequest request = CanonicalCheckoutRequest.of(cartId, "key", "  i-test  ", null);
            assertThat(request.couponCode()).isEqualTo("I-TEST");
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void expectedTotalScalesAreCanonicalizedBeforeHashing() {
        var ten = CanonicalCheckoutRequest.of(cartId, "key", null, new BigDecimal("10"));
        var tenPointZero = CanonicalCheckoutRequest.of(cartId, "key", null, new BigDecimal("10.0"));
        var tenPointZeroZero = CanonicalCheckoutRequest.of(cartId, "key", null, new BigDecimal("10.00"));

        assertThat(ten.expectedTotal()).isEqualByComparingTo("10.00");
        assertThat(hasher.hash(ten)).isEqualTo(hasher.hash(tenPointZero));
        assertThat(hasher.hash(tenPointZero)).isEqualTo(hasher.hash(tenPointZeroZero));
    }

    @Test
    void absentAndPresentMoneyProduceDifferentHashes() {
        var absent = CanonicalCheckoutRequest.of(cartId, "key", null, null);
        var presentZero = CanonicalCheckoutRequest.of(cartId, "key", null, BigDecimal.ZERO);

        assertThat(hasher.hash(absent)).isNotEqualTo(hasher.hash(presentZero));
    }

    @Test
    void absentAndPresentEmptyFieldsHaveDifferentEncodings() {
        var absentCoupon = new CanonicalCheckoutRequest(cartId, "key", null, null);
        var presentEmptyCoupon = new CanonicalCheckoutRequest(cartId, "key", "", null);

        assertThat(hasher.hash(absentCoupon)).isNotEqualTo(hasher.hash(presentEmptyCoupon));
    }

    @Test
    void couponCaseVariantsHashEquallyAfterCanonicalization() {
        var lowercase = CanonicalCheckoutRequest.of(cartId, "key", " reward-1 ", null);
        var uppercase = CanonicalCheckoutRequest.of(cartId, "key", "REWARD-1", null);

        assertThat(hasher.hash(lowercase)).isEqualTo(hasher.hash(uppercase));
    }

    @Test
    void lengthPrefixPreventsAmbiguousCouponAndMoneyConcatenation() {
        var first = CanonicalCheckoutRequest.of(cartId, "key", "A1", new BigDecimal("0.00"));
        var second = CanonicalCheckoutRequest.of(cartId, "key", "A", new BigDecimal("10.00"));

        assertThat(first.couponCode() + first.expectedTotal().toPlainString())
                .isEqualTo(second.couponCode() + second.expectedTotal().toPlainString());
        assertThat(hasher.hash(first)).isNotEqualTo(hasher.hash(second));
    }

    @Test
    void rejectsMoneyWithMoreThanTwoDecimalPlaces() {
        assertThatThrownBy(() -> CanonicalCheckoutRequest.of(cartId, "key", null, new BigDecimal("10.001")))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void presentBlankCouponIsInvalid() {
        assertThatThrownBy(() -> CanonicalCheckoutRequest.of(cartId, "key", "  ", null))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("VALIDATION_FAILED");
    }
}