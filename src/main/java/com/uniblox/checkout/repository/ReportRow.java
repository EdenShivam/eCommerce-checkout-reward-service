package com.uniblox.checkout.repository;

import java.math.BigDecimal;

public interface ReportRow {
    Long getProductId();
    String getProductName();
    Long getPurchasedQuantity();
    BigDecimal getGross();
    BigDecimal getTotalDiscounts();
    BigDecimal getNet();
    Long getCouponsGenerated();
    Long getCouponsAvailable();
    Long getCouponsRedeemed();
    Long getTotalOrders();
}