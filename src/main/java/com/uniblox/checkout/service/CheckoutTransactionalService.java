package com.uniblox.checkout.service;

import com.uniblox.checkout.api.ApiException;
import com.uniblox.checkout.domain.Cart;
import com.uniblox.checkout.domain.CartItem;
import com.uniblox.checkout.domain.CartStatus;
import com.uniblox.checkout.domain.CouponStatus;
import com.uniblox.checkout.domain.IdempotencyRecord;
import com.uniblox.checkout.domain.Order;
import com.uniblox.checkout.repository.CartRepository;
import com.uniblox.checkout.repository.CheckoutCouponView;
import com.uniblox.checkout.repository.CheckoutProductView;
import com.uniblox.checkout.repository.CouponRepository;
import com.uniblox.checkout.repository.IdempotencyRecordRepository;
import com.uniblox.checkout.repository.OrderRepository;
import com.uniblox.checkout.repository.ProductRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class CheckoutTransactionalService {
    private final CartRepository cartRepository;
    private final ProductRepository productRepository;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final OrderRepository orderRepository;
    private final CouponRepository couponRepository;
    private final CheckoutRequestHasher requestHasher;

    public CheckoutTransactionalService(
            CartRepository cartRepository,
            ProductRepository productRepository,
            IdempotencyRecordRepository idempotencyRecordRepository,
            OrderRepository orderRepository,
            CouponRepository couponRepository,
            CheckoutRequestHasher requestHasher) {
        this.cartRepository = cartRepository;
        this.productRepository = productRepository;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.orderRepository = orderRepository;
        this.couponRepository = couponRepository;
        this.requestHasher = requestHasher;
    }

    @Transactional
    public Order execute(CanonicalCheckoutRequest request) {
        if (!cartRepository.existsById(request.cartId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CART_NOT_FOUND", "Cart not found");
        }

        IdempotencyRecord record = new IdempotencyRecord(request.cartId(), request.idempotencyKey(), requestHasher.hash(request));
        idempotencyRecordRepository.saveAndFlush(record);

        int updated = cartRepository.markCheckedOutIfOpen(request.cartId(), CartStatus.CHECKED_OUT, CartStatus.OPEN);
        if (updated == 0) {
            if (!cartRepository.existsById(request.cartId())) {
                throw new ApiException(HttpStatus.NOT_FOUND, "CART_NOT_FOUND", "Cart not found");
            }
            throw new ApiException(HttpStatus.CONFLICT, "CART_ALREADY_CHECKED_OUT", "Cart already checked out");
        }

        Cart cart = cartRepository.findDetailedById(request.cartId()).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CART_NOT_FOUND", "Cart not found"));
        if (cart.getItems().isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "EMPTY_CART", "Cart is empty");
        }

        Map<Long, CheckoutProductView> products = new HashMap<>();
        List<CheckoutPricing.Line> pricingLines = new ArrayList<>();
        for (CartItem item : cart.getItems()) {
            CheckoutProductView product = productRepository.findCheckoutProduct(item.getProductId())
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Product not found"));
            products.put(product.getId(), product);
            pricingLines.add(new CheckoutPricing.Line(product.getUnitPrice(), item.getQuantity()));
        }
        BigDecimal gross = CheckoutPricing.gross(pricingLines);

        BigDecimal discountPercent = null;
        String couponCode = request.couponCode();

        if (couponCode != null) {
            CheckoutCouponView coupon = couponRepository.findCheckoutCoupon(couponCode)
                    .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "COUPON_NOT_FOUND", "Coupon not found"));
            if (coupon.getStatus() == CouponStatus.REDEEMED) {
                throw new ApiException(HttpStatus.CONFLICT, "COUPON_ALREADY_REDEEMED", "Coupon already redeemed");
            }
            discountPercent = coupon.getDiscountPercent();
        }

        CheckoutPricing.Amounts totals = CheckoutPricing.calculate(gross, discountPercent);
        BigDecimal discount = totals.discount();
        BigDecimal net = totals.net();
        if (request.expectedTotal() != null && request.expectedTotal().compareTo(net) != 0) {
            throw new ApiException(HttpStatus.CONFLICT, "PRICE_CHANGED", "Expected total does not match; gross=" + money(gross)
                    + ", discount=" + money(discount) + ", net=" + money(net));
        }

        if (gross.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "EMPTY_CART", "Cart is empty");
        }

        List<Long> productIds = cart.getItems().stream()
                .map(CartItem::getProductId)
                .sorted(Comparator.naturalOrder())
                .toList();

        List<String> unavailableLines = new ArrayList<>();
        for (Long productId : productIds) {
            int requestedQuantity = cart.findItem(productId).getQuantity();
            int affectedRows = productRepository.decrementStockIfAvailable(productId, requestedQuantity);
            if (affectedRows != 1) {
                int available = productRepository.findStockById(productId).orElse(0);
                unavailableLines.add("productId=" + productId + " requested=" + requestedQuantity + " available=" + available);
            }
        }
        if (!unavailableLines.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK", String.join("; ", unavailableLines));
        }

        Order order = new Order(request.cartId(), gross, couponCode, discountPercent, discount, net);
        for (CartItem item : cart.getItems()) {
            CheckoutProductView product = products.get(item.getProductId());
            BigDecimal lineSubtotal = product.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())).setScale(2, RoundingMode.HALF_UP);
            order.addLine(product.getId(), product.getName(), item.getQuantity(), product.getUnitPrice().setScale(2, RoundingMode.HALF_UP), lineSubtotal);
        }
        orderRepository.saveAndFlush(order);

        if (couponCode != null) {
            int redeemed = couponRepository.redeemIfAvailable(couponCode, order.getId(), CouponStatus.AVAILABLE, CouponStatus.REDEEMED);
            if (redeemed != 1) {
                throw new ApiException(HttpStatus.CONFLICT, "COUPON_ALREADY_REDEEMED", "Coupon already redeemed");
            }
        }

        int completed = idempotencyRecordRepository.completeOrder(record.getId(), order.getId());
        if (completed != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "RETRYABLE_CONFLICT", "Idempotency record could not be completed");
        }
        return order;
    }

    private String money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
