package com.ledger.db;

import org.h2.api.Trigger;

import java.sql.Connection;
import java.sql.SQLException;

public class ImmutableTransactionTrigger implements Trigger {
    @Override
    public void fire(Connection conn, Object[] oldRow, Object[] newRow) throws SQLException {
        throw new SQLException("transactions are immutable and cannot be updated or deleted", "45000");
    }
}
