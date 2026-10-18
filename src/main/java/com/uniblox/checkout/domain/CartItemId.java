package com.uniblox.checkout.domain;
import jakarta.persistence.*;
import java.io.Serializable;
import java.util.UUID;
import java.util.Objects;
@Embeddable public class CartItemId implements Serializable { @Column(name="cart_id") UUID cartId; @Column(name="product_id") Long productId; protected CartItemId(){} CartItemId(UUID c, Long p){cartId=c;productId=p;} @Override public boolean equals(Object o){return o instanceof CartItemId other && Objects.equals(cartId,other.cartId)&&Objects.equals(productId,other.productId);}@Override public int hashCode(){return Objects.hash(cartId,productId);} }
