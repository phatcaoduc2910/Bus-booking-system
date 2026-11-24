package com.example.busbooking;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Profiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

public class PostgresTestcontainerInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static volatile PostgreSQLContainer postgres;

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        if (!context.getEnvironment().acceptsProfiles(Profiles.of("test"))) {
            return;
        }
        // Keep external DB override working when TEST_DB_URL is explicitly supplied (e.g. CI/Kaggle without Docker).
        String externalUrl = System.getenv("TEST_DB_URL");
        if (externalUrl != null && !externalUrl.isBlank()) {
            return;
        }

        PostgreSQLContainer container = getOrStartContainer();
        TestPropertyValues.of(
                "spring.datasource.url=" + container.getJdbcUrl(),
                "spring.datasource.username=" + container.getUsername(),
                "spring.datasource.password=" + container.getPassword()
        ).applyTo(context.getEnvironment());
    }

    private static PostgreSQLContainer getOrStartContainer() {
        if (postgres == null) {
            synchronized (PostgresTestcontainerInitializer.class) {
                if (postgres == null) {
                    PostgreSQLContainer container = new PostgreSQLContainer("postgres:16")
                            .withDatabaseName("busbooking_test")
                            .withUsername("busbooking_test")
                            .withPassword("TestOnlyBusBooking_2026");
                    container.start();
                    postgres = container;
                }
            }
        }
        return postgres;
    }
}
