package de.augmentia.oc.engine.adapter.sql;

import de.augmentia.oc.engine.core.port.runtime.Clock;
import de.augmentia.oc.engine.core.event.EventCodec;

import javax.sql.DataSource;

/** PostgreSQL event log; sequences are gapless per aggregate. */
public final class SqlJournal extends AbstractJdbcJournal {

    public SqlJournal(DataSource dataSource, Clock clock) {
        this(dataSource, clock, EventCodec.standard());
    }

    public SqlJournal(DataSource dataSource, Clock clock, EventCodec codec) {
        super(dataSource, clock, codec, PostgresDialect.INSTANCE);
    }
}
