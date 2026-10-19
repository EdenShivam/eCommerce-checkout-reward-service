package com.uniblox.checkout.repository;

import com.uniblox.checkout.domain.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {
    @Query(value = """
            SELECT p.id AS productId, p.name AS productName,
                   COALESCE((SELECT SUM(ol.quantity) FROM order_lines ol WHERE ol.product_id = p.id), 0) AS purchasedQuantity,
                   COALESCE((SELECT SUM(o.gross) FROM orders o WHERE o.status = 'PLACED'), 0) AS gross,
                   COALESCE((SELECT SUM(o.discount) FROM orders o WHERE o.status = 'PLACED'), 0) AS totalDiscounts,
                   COALESCE((SELECT SUM(o.net) FROM orders o WHERE o.status = 'PLACED'), 0) AS net,
                   (SELECT COUNT(*) FROM coupons) AS couponsGenerated,
                   (SELECT COUNT(*) FROM coupons c WHERE c.status = 'AVAILABLE') AS couponsAvailable,
                   (SELECT COUNT(*) FROM coupons c WHERE c.status = 'REDEEMED') AS couponsRedeemed,
                   (SELECT COUNT(*) FROM orders o WHERE o.status = 'PLACED') AS totalOrders
            FROM products p
            ORDER BY p.id
            """, nativeQuery = true)
    List<ReportRow> getReportSnapshot();

    @Query("select p.id as id, p.name as name, p.unitPrice as unitPrice from Product p where p.id = :id")
    Optional<CheckoutProductView> findCheckoutProduct(@Param("id") Long id);

    @Query("select p.stock from Product p where p.id = :id")
    Optional<Integer> findStockById(@Param("id") Long id);

    @Modifying(flushAutomatically = true)
    @Query("update Product p set p.stock = p.stock - :quantity where p.id = :id and p.stock >= :quantity")
    int decrementStockIfAvailable(@Param("id") Long id, @Param("quantity") int quantity);
}
