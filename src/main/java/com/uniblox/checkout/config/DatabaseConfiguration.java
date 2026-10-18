package com.uniblox.checkout.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
class DatabaseConfiguration {

    @Bean
    DataSource dataSource(DataSourceProperties properties, AppDatabaseProperties databaseProperties) {
        HikariDataSource dataSource = properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        dataSource.setJdbcUrl(properties.determineUrl() + ";LOCK_TIMEOUT=" + databaseProperties.lockTimeoutMs());
        return dataSource;
    }
}
