package de.augmentia.oc.engine.adapter.sqlite;

import de.augmentia.oc.engine.adapter.sql.AbstractJdbcSnapshots;
import de.augmentia.oc.engine.core.port.world.FileSystem;

import javax.sql.DataSource;

/** SQLite snapshot metadata and file contents; restore uses the FileSystem port. */
public final class SqliteSnapshots extends AbstractJdbcSnapshots {

    public SqliteSnapshots(DataSource dataSource, FileSystem fileSystem) {
        super(dataSource, fileSystem, SqliteDialect.INSTANCE);
    }
}
