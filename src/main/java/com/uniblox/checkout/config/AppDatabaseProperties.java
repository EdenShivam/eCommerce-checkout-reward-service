package com.uniblox.checkout.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.db")
public record AppDatabaseProperties(
        @NotNull(message = "app.db.lock-timeout-ms must be provided")
        @Min(value = 1, message = "app.db.lock-timeout-ms must be greater than 0")
        @Max(value = 60_000, message = "app.db.lock-timeout-ms must not exceed 60000")
        Integer lockTimeoutMs
) {
}
