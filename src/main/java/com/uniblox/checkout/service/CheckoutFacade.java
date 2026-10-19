package com.uniblox.checkout.service;

import com.uniblox.checkout.api.ApiException;
import com.uniblox.checkout.domain.IdempotencyRecord;
import com.uniblox.checkout.domain.Order;
import com.uniblox.checkout.repository.IdempotencyRecordRepository;
import com.uniblox.checkout.repository.OrderRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;

@Service
public class CheckoutFacade {
    private final CheckoutTransactionalService transactionalService;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final OrderRepository orderRepository;
    private final CheckoutRequestHasher requestHasher;

    public CheckoutFacade(
            CheckoutTransactionalService transactionalService,
            IdempotencyRecordRepository idempotencyRecordRepository,
            OrderRepository orderRepository,
            CheckoutRequestHasher requestHasher) {
        this.transactionalService = transactionalService;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.orderRepository = orderRepository;
        this.requestHasher = requestHasher;
    }

    public Order checkout(UUID cartId, String key, String couponCode, BigDecimal expectedTotal) {
        return checkoutResult(cartId, key, couponCode, expectedTotal).order();
    }

    public CheckoutResult checkoutResult(UUID cartId, String key, String couponCode, BigDecimal expectedTotal) {
        CanonicalCheckoutRequest request = CanonicalCheckoutRequest.of(cartId, key, couponCode, expectedTotal);

        IdempotencyRecord existing = idempotencyRecordRepository.findByCartIdAndKey(cartId, key).orElse(null);
        if (existing != null) {
            if (existing.getOrderId() != null) {
                if (requestHasher.hash(request).equals(existing.getRequestHash())) {
                    Order order = orderRepository.findById(existing.getOrderId())
                            .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "RETRYABLE_CONFLICT", "Recorded order was not found"));
                    return new CheckoutResult(order, true);
                }
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED", "Idempotency key reused with a different request");
            }
            throw new ApiException(HttpStatus.CONFLICT, "RETRYABLE_CONFLICT", "Checkout in progress");
        }

        try {
            return new CheckoutResult(transactionalService.execute(request), false);
        } catch (DataIntegrityViolationException e) {
            if (!isIdempotencyConflict(e)) {
                throw e;
            }

            IdempotencyRecord record = idempotencyRecordRepository.findByCartIdAndKey(cartId, key)
                    .orElse(null);
            if (record == null || record.getOrderId() == null) {
                throw new ApiException(HttpStatus.CONFLICT, "RETRYABLE_CONFLICT", "Checkout in progress");
            }
            if (!requestHasher.hash(request).equals(record.getRequestHash())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED", "Idempotency key reused with a different request");
            }
            Order order = orderRepository.findById(record.getOrderId())
                    .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "RETRYABLE_CONFLICT", "Recorded order was not found"));
            return new CheckoutResult(order, true);
        } catch (RuntimeException exception) {
            if (DatabaseFailureClassifier.isRetryableLockFailure(exception)) {
                throw new ApiException(HttpStatus.CONFLICT, "RETRYABLE_CONFLICT", "Database lock conflict; retry the request");
            }
            throw exception;
        }
    }

    public record CheckoutResult(Order order, boolean replayed) {}

    private boolean isIdempotencyConflict(DataIntegrityViolationException exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException violation) {
                String constraintName = violation.getConstraintName();
                if (constraintName != null && constraintName.toLowerCase(Locale.ROOT).endsWith("uk_idempotency_cart_key")) {
                    return true;
                }
            }
            String message = cause.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("uk_idempotency_cart_key")) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
