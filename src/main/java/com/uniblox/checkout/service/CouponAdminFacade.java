package com.uniblox.checkout.service;

import com.uniblox.checkout.api.ApiException;
import com.uniblox.checkout.config.RewardsProperties;
import com.uniblox.checkout.domain.Coupon;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class CouponAdminFacade {
    private final CouponGenerationService generationService;
    private final RewardsProperties rewardsProperties;

    public CouponAdminFacade(CouponGenerationService generationService, RewardsProperties rewardsProperties) {
        this.generationService = generationService;
        this.rewardsProperties = rewardsProperties;
    }

    public Coupon generateNext() {
        int milestoneConflicts = 0;
        int codeCollisions = 0;
        while (milestoneConflicts < rewardsProperties.generationMaxAttempts()
                && codeCollisions < rewardsProperties.codeMaxAttempts()) {
            try {
                return generationService.tryGenerate();
            } catch (DataIntegrityViolationException exception) {
                String cause = mostSpecificMessage(exception);
                if (identifies(cause, "uk_coupon_milestone")) {
                    milestoneConflicts++;
                } else if (identifies(cause, "uk_coupon_code")) {
                    codeCollisions++;
                } else {
                    throw exception;
                }
            } catch (RuntimeException exception) {
                if (DatabaseFailureClassifier.isRetryableLockFailure(exception)) {
                    throw new ApiException(HttpStatus.CONFLICT, "RETRYABLE_CONFLICT", "Database lock conflict; retry the request");
                }
                throw exception;
            }
        }
        throw new ApiException(HttpStatus.CONFLICT, "RETRYABLE_CONFLICT", "Coupon generation retry budget exhausted");
    }

    private String mostSpecificMessage(DataIntegrityViolationException exception) {
        Throwable cause = exception.getMostSpecificCause();
        return cause == null || cause.getMessage() == null ? "" : cause.getMessage().toLowerCase(Locale.ROOT);
    }

    private boolean identifies(String message, String constraintName) {
        return message.contains(constraintName.toLowerCase(Locale.ROOT));
    }
}