package com.uniblox.checkout.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

@Validated
@ConfigurationProperties(prefix = "rewards")
public record RewardsProperties(
        @DefaultValue("5") @Min(1) int n,
        @DefaultValue("10") @DecimalMin(value = "0", inclusive = false) @DecimalMax("100") BigDecimal discountPercent,
        @DefaultValue("3") @Min(1) int generationMaxAttempts,
        @DefaultValue("5") @Min(1) int codeMaxAttempts
) {
}
