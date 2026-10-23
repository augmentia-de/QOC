package de.augmentia.oc.engine.http;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.flywaydb.core.Flyway;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

/** Runs the database migration chosen by ENGINE_DB_KIND at application startup. */
@ApplicationScoped
public class DatabaseMigration {

    @Inject
    DataSource dataSource;

    @Inject
    @ConfigProperty(name = "engine.db.kind", defaultValue = "postgresql")
    String dbKind;

    void onStart(@Observes StartupEvent event) {
        String location = "sqlite".equalsIgnoreCase(dbKind)
                ? "classpath:db/migration-sqlite"
                : "classpath:db/migration";
        Flyway.configure().dataSource(dataSource).locations(location).load().migrate();

        if ("sqlite".equalsIgnoreCase(dbKind)) {
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA foreign_keys=ON");
            } catch (Exception e) {
                throw new RuntimeException("cannot configure sqlite", e);
            }
        }
    }
}
