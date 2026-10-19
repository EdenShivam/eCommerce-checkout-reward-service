package com.uniblox.checkout.domain;
import jakarta.persistence.*; import java.time.Instant; import java.util.UUID;
@Entity @Table(name="idempotency_records",uniqueConstraints=@UniqueConstraint(name="uk_idempotency_cart_key",columnNames={"cart_id","idempotency_key"})) public class IdempotencyRecord {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id; @Column(name="cart_id",nullable=false) private UUID cartId; @Column(name="idempotency_key",nullable=false) private String key; @Column(name="request_hash",nullable=false) private String requestHash; @Column(name="order_id") private UUID orderId; @Column(name="created_at",nullable=false) private Instant createdAt;
 protected IdempotencyRecord(){} public IdempotencyRecord(UUID c,String k,String h){cartId=c;key=k;requestHash=h;createdAt=Instant.now();} public Long getId(){return id;} public String getRequestHash(){return requestHash;} public UUID getOrderId(){return orderId;} public void complete(UUID id){orderId=id;}
}
