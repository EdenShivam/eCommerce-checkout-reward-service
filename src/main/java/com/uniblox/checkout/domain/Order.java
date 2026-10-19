package com.uniblox.checkout.domain;
import jakarta.persistence.*; import java.math.*; import java.time.*; import java.util.*;
@Entity @Table(name = "orders")
public class Order {
    @Id private UUID id;
    @Column(name = "cart_id") private UUID cartId;
    @Column(nullable = false) private String status;
    @Column(nullable = false) private BigDecimal gross;
    @Column(nullable = false) private BigDecimal discount;
    @Column(nullable = false) private BigDecimal net;
    @Column(name = "coupon_code") private String couponCode;
    @Column(name = "discount_percent") private BigDecimal discountPercent;
    @Column(name = "created_at") private Instant createdAt;
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<OrderLine> lines = new ArrayList<>();

    protected Order() {}

    public Order(UUID cartId, BigDecimal gross) {
        this.id = UUID.randomUUID();
        this.cartId = cartId;
        this.gross = gross.setScale(2, RoundingMode.HALF_UP);
        this.discount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        this.net = this.gross;
        this.couponCode = null;
        this.discountPercent = null;
        this.status = "PLACED";
        this.createdAt = Instant.now();
    }

    public Order(UUID cartId, BigDecimal gross, String couponCode, BigDecimal discountPercent, BigDecimal discount, BigDecimal net) {
        this.id = UUID.randomUUID();
        this.cartId = cartId;
        this.gross = gross.setScale(2, RoundingMode.HALF_UP);
        this.discount = discount.setScale(2, RoundingMode.HALF_UP);
        this.net = net.setScale(2, RoundingMode.HALF_UP);
        this.couponCode = couponCode;
        this.discountPercent = discountPercent == null ? null : discountPercent.setScale(2, RoundingMode.HALF_UP);
        this.status = "PLACED";
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getCartId() { return cartId; }
    public String getStatus() { return status; }
    public BigDecimal getGross() { return gross; }
    public BigDecimal getDiscount() { return discount; }
    public BigDecimal getNet() { return net; }
    public String getCouponCode() { return couponCode; }
    public BigDecimal getDiscountPercent() { return discountPercent; }
    public Instant getCreatedAt() { return createdAt; }
    public List<OrderLine> getLines() { return List.copyOf(lines); }
    public void addLine(Long productId, String productName, int quantity, BigDecimal unitPrice, BigDecimal lineSubtotal) {
        lines.add(new OrderLine(this, productId, productName, quantity, unitPrice, lineSubtotal));
    }
}
