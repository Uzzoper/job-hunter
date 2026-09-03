package com.juanperuzzo.job_hunter.infrastructure.config;

import com.juanperuzzo.job_hunter.JobHunterApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Creates the parent directory of the SQLite database file before the
 * datasource boots, so a missing {@code data/} folder fails fast with a clear
 * message instead of an opaque {@code SQLITE_CANTOPEN}.
 *
 * <p>Reads {@code spring.datasource.url} from the Spring {@link ConfigurableEnvironment}
 * (which already resolves {@code ${DB_URL:...}} from {@code application.yaml}),
 * instead of reading {@code System.getenv()} directly in {@code main}.
 */
public class SqliteDirectoryInitializer implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String dbUrl = environment.getProperty("spring.datasource.url");
        JobHunterApplication.ensureSqliteDirectoryExists(dbUrl);
    }
}
