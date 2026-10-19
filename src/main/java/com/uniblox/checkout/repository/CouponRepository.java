package com.uniblox.checkout.repository;

import com.uniblox.checkout.domain.Coupon;
import com.uniblox.checkout.domain.CouponStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CouponRepository extends JpaRepository<Coupon, Long> {
    Optional<Coupon> findByCode(String code);
    Optional<Coupon> findByMilestone(Integer milestone);
    @Query("select c.code as code, c.discountPercent as discountPercent, c.status as status from Coupon c where c.code = :code")
    Optional<CheckoutCouponView> findCheckoutCoupon(@Param("code") String code);
    long countByStatus(CouponStatus status);

    @Modifying(flushAutomatically = true)
    @Query("update Coupon c set c.status = :redeemed, c.redeemedOrderId = :orderId where c.code = :code and c.status = :available")
    int redeemIfAvailable(@Param("code") String code,
                          @Param("orderId") java.util.UUID orderId,
                          @Param("available") CouponStatus available,
                          @Param("redeemed") CouponStatus redeemed);
}
