package com.uniblox.checkout.repository;

import com.uniblox.checkout.domain.CouponStatus;

import java.math.BigDecimal;

public interface CheckoutCouponView {
    String getCode();
    BigDecimal getDiscountPercent();
    CouponStatus getStatus();
}