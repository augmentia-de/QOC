package de.augmentia.oc.engine.adapter.sqlite;

import de.augmentia.oc.engine.adapter.sql.AbstractJdbcJournal;
import de.augmentia.oc.engine.core.port.runtime.Clock;
import de.augmentia.oc.engine.core.event.EventCodec;

import javax.sql.DataSource;

/** SQLite event log; sequences are gapless per aggregate. */
public final class SqliteJournal extends AbstractJdbcJournal {

    public SqliteJournal(DataSource dataSource, Clock clock) {
        this(dataSource, clock, EventCodec.standard());
    }

    public SqliteJournal(DataSource dataSource, Clock clock, EventCodec codec) {
        super(dataSource, clock, codec, SqliteDialect.INSTANCE);
    }
}
