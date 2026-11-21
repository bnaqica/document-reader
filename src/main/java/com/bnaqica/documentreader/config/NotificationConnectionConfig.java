package com.bnaqica.documentreader.config;

import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The listening connection reaches the same database as the pool: {@link JdbcConnectionDetails} holds what the pool
 * uses, whether it comes from {@code spring.datasource.*} or a Testcontainers service connection.
 */
@Configuration
public class NotificationConnectionConfig {

    @Bean
    NotificationConnectionFactory notificationConnectionFactory(JdbcConnectionDetails details) {
        return new NotificationConnectionFactory(details.getJdbcUrl(), details.getUsername(), details.getPassword());
    }
}
