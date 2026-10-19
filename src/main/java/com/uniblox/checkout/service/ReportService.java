package com.uniblox.checkout.service;

import com.uniblox.checkout.repository.ProductRepository;
import com.uniblox.checkout.repository.ReportRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class ReportService {
    private final ProductRepository productRepository;

    public ReportService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReportResponse getReport() {
        List<ReportRow> rows = productRepository.getReportSnapshot();
        if (rows.isEmpty()) {
            return new ReportResponse(List.of(), money(BigDecimal.ZERO), money(BigDecimal.ZERO), money(BigDecimal.ZERO), 0, 0, 0, 0);
        }
        ReportRow totals = rows.getFirst();
        List<PurchasedQuantity> purchased = rows.stream()
                .map(row -> new PurchasedQuantity(row.getProductId(), row.getProductName(), row.getPurchasedQuantity().intValue()))
                .toList();
        return new ReportResponse(
                purchased,
                money(totals.getGross()),
                money(totals.getTotalDiscounts()),
                money(totals.getNet()),
                totals.getCouponsGenerated(),
                totals.getCouponsAvailable(),
                totals.getCouponsRedeemed(),
                totals.getTotalOrders());
    }

    private static String money(BigDecimal value) {
        return value.setScale(2).toPlainString();
    }

    public record PurchasedQuantity(Long productId, String productName, int purchasedQuantity) {}

    public record ReportResponse(List<PurchasedQuantity> purchasedQuantityByProduct,
                                 String gross,
                                 String totalDiscounts,
                                 String net,
                                 long couponsGenerated,
                                 long couponsAvailable,
                                 long couponsRedeemed,
                                 long totalOrders) {}
}