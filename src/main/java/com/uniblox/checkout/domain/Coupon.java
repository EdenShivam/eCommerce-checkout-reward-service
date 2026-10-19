package com.uniblox.checkout.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "coupons",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_coupon_milestone", columnNames = "milestone"),
        @UniqueConstraint(name = "uk_coupon_code", columnNames = "code")
    }
)
public class Coupon {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, length = 64)
    private String code;

    @Column(nullable = false)
    private Integer milestone;

    @Column(name = "discount_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal discountPercent;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private CouponStatus status;

    @Column(name = "redeemed_order_id")
    private UUID redeemedOrderId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Coupon() {
    }

    public Coupon(String code, Integer milestone, BigDecimal discountPercent, CouponStatus status) {
        this.code = code;
        this.milestone = milestone;
        this.discountPercent = discountPercent;
        this.status = status;
        this.createdAt = Instant.now();
    }

    public String getCode() {
        return code;
    }

    public Long getId() { return id; }

    public Integer getMilestone() {
        return milestone;
    }

    public BigDecimal getDiscountPercent() {
        return discountPercent;
    }

    public CouponStatus getStatus() {
        return status;
    }

    public UUID getRedeemedOrderId() {
        return redeemedOrderId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void redeem(UUID orderId) {
        this.status = CouponStatus.REDEEMED;
        this.redeemedOrderId = orderId;
    }
}
