package com.uniblox.checkout.domain;
import jakarta.persistence.*;
@Entity @Table(name="cart_items") public class CartItem {
 @EmbeddedId private CartItemId id;
 @MapsId("cartId") @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="cart_id") private Cart cart;
 @Column(nullable=false) private int quantity;
 protected CartItem(){} CartItem(Cart c,Long productId,int q){cart=c;id=new CartItemId(c.getId(),productId);quantity=q;}
 public Long getProductId(){return id.productId;} public int getQuantity(){return quantity;} void add(int q){quantity=Math.addExact(quantity,q);} void setQuantity(int q){quantity=q;}
}
