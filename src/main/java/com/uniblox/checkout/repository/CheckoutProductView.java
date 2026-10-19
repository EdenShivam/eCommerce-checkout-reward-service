package com.uniblox.checkout.repository;

import java.math.BigDecimal;

public interface CheckoutProductView {
    Long getId();
    String getName();
    BigDecimal getUnitPrice();
}