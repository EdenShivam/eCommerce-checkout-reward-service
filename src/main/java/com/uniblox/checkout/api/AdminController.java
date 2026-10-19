package com.uniblox.checkout.api;

import com.uniblox.checkout.domain.Coupon;
import com.uniblox.checkout.domain.CouponStatus;
import com.uniblox.checkout.service.CouponAdminFacade;
import com.uniblox.checkout.service.ReportService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.RoundingMode;
import java.time.Instant;

@RestController
@RequestMapping("/admin")
public class AdminController {
    private final CouponAdminFacade couponAdminFacade;
    private final ReportService reportService;

    public AdminController(CouponAdminFacade couponAdminFacade, ReportService reportService) {
        this.couponAdminFacade = couponAdminFacade;
        this.reportService = reportService;
    }

    @PostMapping("/coupons/generate")
    public ResponseEntity<CouponView> generateCoupon() {
        return ResponseEntity.status(HttpStatus.CREATED).body(CouponView.from(couponAdminFacade.generateNext()));
    }

    @GetMapping("/report")
    public ReportService.ReportResponse getReport() {
        return reportService.getReport();
    }

    public record CouponView(String code, int milestone, String discountPercent, CouponStatus status,
                             java.util.UUID redeemedOrderId, Instant createdAt) {
        static CouponView from(Coupon coupon) {
            return new CouponView(coupon.getCode(), coupon.getMilestone(),
                    coupon.getDiscountPercent().setScale(2, RoundingMode.HALF_UP).toPlainString(),
                    coupon.getStatus(), coupon.getRedeemedOrderId(), coupon.getCreatedAt());
        }
    }
}