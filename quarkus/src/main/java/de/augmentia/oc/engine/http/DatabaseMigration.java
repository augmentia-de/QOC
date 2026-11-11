package de.augmentia.oc.engine.http;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.flywaydb.core.Flyway;
import org.jboss.logging.Logger;

import javax.sql.DataSource;

/** Runs the database migration chosen by ENGINE_DB_KIND at application startup. */
@ApplicationScoped
public class DatabaseMigration {

    private static final Logger LOG = Logger.getLogger(DatabaseMigration.class);

    @Inject
    Instance<DataSource> dataSource;

    @Inject
    Instance<Flyway> flyway;

    @Inject
    @ConfigProperty(name = "engine.db.kind", defaultValue = "postgresql")
    String dbKind;

    @Inject
    @ConfigProperty(name = "engine.storage", defaultValue = "")
    String storageKind;

    void onStart(@Observes StartupEvent event) {
        // In-memory mode: no database, no migration, no Flyway.
        if ("memory".equalsIgnoreCase(storageKind)) {
            return;
        }
        if (!dataSource.isResolvable()) {
            LOG.warn("No active datasource; skipping database migration");
            return;
        }
        if (isNativeImage()) {
            // In native images Flyway's classpath directory scanning is replaced by
            // Quarkus' build-time migration registry. Use the Quarkus-managed
            // Flyway bean so the registered SQLite migrations are visible.
            if (flyway.isResolvable()) {
                flyway.get().migrate();
            } else {
                LOG.warn("No active Flyway bean; skipping database migration");
                return;
            }
        } else {
            String location = "sqlite".equalsIgnoreCase(dbKind)
                    ? "classpath:db/migration-sqlite"
                    : "classpath:db/migration";
            Flyway.configure().dataSource(dataSource.get()).locations(location).load().migrate();
        }

        if ("sqlite".equalsIgnoreCase(dbKind)) {
            de.augmentia.oc.engine.adapter.sql.DbConfigSql.configureSqlite(dataSource.get());
        }
    }

    private static boolean isNativeImage() {
        return System.getProperty("org.graalvm.nativeimage.imagecode") != null;
    }
}
