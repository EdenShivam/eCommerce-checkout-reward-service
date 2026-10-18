package com.uniblox.checkout.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigurationPropertiesValidationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void defaultsForRewardsAreValid() {
        contextRunner.withPropertyValues("app.db.lock-timeout-ms=5000")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(RewardsProperties.class))
                            .isEqualTo(new RewardsProperties(5, java.math.BigDecimal.TEN, 3, 5));
                });
    }

    @Test
    void databaseLockTimeoutIsRequiredAndPositive() {
        assertInvalidDatabaseTimeout();
        assertInvalidDatabaseTimeout("app.db.lock-timeout-ms=0");
        assertInvalidDatabaseTimeout("app.db.lock-timeout-ms=-1");
        assertInvalidDatabaseTimeout("app.db.lock-timeout-ms=not-a-number");
        assertInvalidDatabaseTimeout("app.db.lock-timeout-ms=60001");
    }

    @Test
    void rewardsPropertiesRejectInvalidValues() {
        assertInvalidRewardProperty("rewards.n=0");
        assertInvalidRewardProperty("rewards.discount-percent=0");
        assertInvalidRewardProperty("rewards.discount-percent=100.01");
        assertInvalidRewardProperty("rewards.generation-max-attempts=0");
        assertInvalidRewardProperty("rewards.code-max-attempts=0");
    }

    private void assertInvalidDatabaseTimeout(String... propertyValues) {
        contextRunner.withPropertyValues(propertyValues).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("app.db.lock-timeout-ms");
        });
    }

    private void assertInvalidRewardProperty(String property) {
        contextRunner.withPropertyValues("app.db.lock-timeout-ms=5000", property).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("rewards");
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({AppDatabaseProperties.class, RewardsProperties.class})
    static class PropertiesConfiguration {
    }
}
