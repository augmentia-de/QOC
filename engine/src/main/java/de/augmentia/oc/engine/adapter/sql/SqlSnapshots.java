package de.augmentia.oc.engine.adapter.sql;

import de.augmentia.oc.engine.core.port.world.FileSystem;

import javax.sql.DataSource;

/** PostgreSQL snapshot metadata and file contents; restore uses the FileSystem port. */
public final class SqlSnapshots extends AbstractJdbcSnapshots {

    public SqlSnapshots(DataSource dataSource, FileSystem fileSystem) {
        super(dataSource, fileSystem, PostgresDialect.INSTANCE);
    }
}
