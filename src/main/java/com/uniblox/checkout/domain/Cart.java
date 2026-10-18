package com.uniblox.checkout.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

@Entity @Table(name = "carts")
public class Cart {
    @Id private UUID id;
    @Column(nullable = false) @Enumerated(EnumType.STRING) private CartStatus status;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true) private List<CartItem> items = new ArrayList<>();
    protected Cart() {}
    public Cart(UUID id) { this.id = id; this.status = CartStatus.OPEN; this.createdAt = Instant.now(); }
    public UUID getId() { return id; }
    public CartStatus getStatus() { return status; }
    public List<CartItem> getItems() { return items; }
    public CartItem findItem(long productId) { return items.stream().filter(i -> i.getProductId().equals(productId)).findFirst().orElse(null); }
    public void add(Product p, int q) { CartItem existing = findItem(p.getId()); if (existing == null) items.add(new CartItem(this, p.getId(), q)); else existing.add(q); }
    public void set(long productId, int q) { findItem(productId).setQuantity(q); }
    public void remove(long productId) { items.removeIf(i -> i.getProductId().equals(productId)); }
}
