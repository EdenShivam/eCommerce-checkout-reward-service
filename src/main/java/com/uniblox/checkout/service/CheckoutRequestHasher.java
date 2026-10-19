package com.uniblox.checkout.service;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class CheckoutRequestHasher {
    public String hash(CanonicalCheckoutRequest request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String canonical = "v1|" + field(request.cartId().toString()) + "|" + field(request.couponCode()) + "|" + field(request.expectedTotal() == null ? null : request.expectedTotal().setScale(2).toPlainString());
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private String field(String value) {
        if (value == null) {
            return "N";
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return bytes.length + ":" + value;
    }
}
