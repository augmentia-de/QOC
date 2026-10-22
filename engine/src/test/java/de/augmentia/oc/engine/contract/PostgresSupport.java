package de.augmentia.oc.engine.contract;

import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;

/** One PostgreSQL container for the whole contract suite. */
public final class PostgresSupport {

    private static PostgreSQLContainer<?> container;
    private static DataSource dataSource;

    private PostgresSupport() {}

    public static synchronized DataSource dataSource() {
        if (dataSource == null) {
            container = new PostgreSQLContainer<>("postgres:16-alpine");
            container.start();
            PGSimpleDataSource source = new PGSimpleDataSource();
            source.setURL(container.getJdbcUrl());
            source.setUser(container.getUsername());
            source.setPassword(container.getPassword());
            Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
            dataSource = source;
        }
        return dataSource;
    }
}
