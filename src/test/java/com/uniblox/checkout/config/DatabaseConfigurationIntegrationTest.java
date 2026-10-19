package com.uniblox.checkout.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "spring.test.mock.mockito.reset=none",
    "spring.datasource.url=jdbc:h2:mem:database-config-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"
})
class DatabaseConfigurationIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void configuresLockTimeoutButH2DoesNotExposeItThroughInformationSchema() {
        assertThat(((HikariDataSource) dataSource).getJdbcUrl()).contains("LOCK_TIMEOUT=5000");

        List<String> lockTimeoutSettings = jdbcTemplate.queryForList(
                "SELECT SETTING_VALUE FROM INFORMATION_SCHEMA.SETTINGS WHERE SETTING_NAME = 'LOCK_TIMEOUT'",
                String.class
        );

        assertThat(lockTimeoutSettings).isEmpty();
    }

    @Test
    void seedsTheRequiredProductCatalog() {
        Integer productCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM products", Integer.class);
        Integer limitedStock = jdbcTemplate.queryForObject(
                "SELECT stock FROM products WHERE name = 'Limited Edition Mug'", Integer.class
        );

        assertThat(productCount).isGreaterThanOrEqualTo(5);
        assertThat(limitedStock).isEqualTo(3);
    }
}
