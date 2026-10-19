package com.uniblox.checkout.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

@Component
public class RandomCouponCodeGenerator implements CouponCodeGenerator {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public String nextCode() {
        StringBuilder code = new StringBuilder(12);
        for (int index = 0; index < 12; index++) {
            code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }
}