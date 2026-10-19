package com.uniblox.checkout.service;

@FunctionalInterface
public interface CouponCodeGenerator {
    String nextCode();
}