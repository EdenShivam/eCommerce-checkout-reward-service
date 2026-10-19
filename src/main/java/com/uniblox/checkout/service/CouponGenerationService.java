package com.uniblox.checkout.service;

import com.uniblox.checkout.api.ApiException;
import com.uniblox.checkout.config.RewardsProperties;
import com.uniblox.checkout.domain.Coupon;
import com.uniblox.checkout.domain.CouponStatus;
import com.uniblox.checkout.repository.CouponRepository;
import com.uniblox.checkout.repository.OrderRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;

@Service
public class CouponGenerationService {
    private final OrderRepository orderRepository;
    private final CouponRepository couponRepository;
    private final RewardsProperties rewardsProperties;
    private final CouponCodeGenerator codeGenerator;

    public CouponGenerationService(OrderRepository orderRepository,
                                   CouponRepository couponRepository,
                                   RewardsProperties rewardsProperties,
                                   CouponCodeGenerator codeGenerator) {
        this.orderRepository = orderRepository;
        this.couponRepository = couponRepository;
        this.rewardsProperties = rewardsProperties;
        this.codeGenerator = codeGenerator;
    }

    @Transactional
    public Coupon tryGenerate() {
        long placedOrders = orderRepository.countByStatus("PLACED");
        int eligibleMilestones = (int) (placedOrders / rewardsProperties.n());
        Set<Integer> existingMilestones = new HashSet<>();
        couponRepository.findAll().forEach(coupon -> existingMilestones.add(coupon.getMilestone()));

        int milestone = 1;
        while (milestone <= eligibleMilestones && existingMilestones.contains(milestone)) {
            milestone++;
        }
        if (milestone > eligibleMilestones) {
            throw new ApiException(HttpStatus.CONFLICT, "NO_ELIGIBLE_MILESTONE", "No eligible unrewarded milestone");
        }

        Coupon coupon = new Coupon(codeGenerator.nextCode(), milestone, rewardsProperties.discountPercent(), CouponStatus.AVAILABLE);
        return couponRepository.saveAndFlush(coupon);
    }
}