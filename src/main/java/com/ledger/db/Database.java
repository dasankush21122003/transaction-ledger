package com.ledger.db;

import com.ledger.exception.LedgerException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

public final class Database {

    @FunctionalInterface
    public interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    private static final String OPTIONS = ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000";
    private static final String UNIQUE_VIOLATION = "23505";

    private final String url;

    public Database(String url) {
        this.url = url;
        initSchema();
    }

    public static Database file(String path) {
        return new Database("jdbc:h2:file:" + path + OPTIONS);
    }

    public static Database inMemory() {
        return new Database("jdbc:h2:mem:" + UUID.randomUUID() + OPTIONS);
    }

    public <T> T inTransaction(Work<T> work) {
        try (Connection c = DriverManager.getConnection(url)) {
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException e) {
                c.rollback();
                throw translate(e);
            } catch (RuntimeException | Error e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw translate(e);
        }
    }

    private static RuntimeException translate(SQLException e) {
        if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
            return LedgerException.conflict("duplicate value violates a uniqueness constraint");
        }
        return new IllegalStateException("database error", e);
    }

    private void initSchema() {
        inTransaction(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("""
                        CREATE TABLE IF NOT EXISTS accounts (
                            id         VARCHAR(36)  PRIMARY KEY,
                            name       VARCHAR(255) NOT NULL,
                            balance    DECIMAL(19,2) NOT NULL DEFAULT 0,
                            created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                            CONSTRAINT chk_accounts_name    CHECK (LENGTH(TRIM(name)) > 0),
                            CONSTRAINT chk_accounts_balance CHECK (balance >= 0)
                        )""");
                st.execute("""
                        CREATE TABLE IF NOT EXISTS transactions (
                            seq          BIGINT GENERATED ALWAYS AS IDENTITY,
                            id           VARCHAR(36)   PRIMARY KEY,
                            account_id   VARCHAR(36)   NOT NULL,
                            tx_type      VARCHAR(6)    NOT NULL,
                            amount       DECIMAL(19,2) NOT NULL,
                            description  VARCHAR(500),
                            reference_id VARCHAR(100)  NOT NULL,
                            transfer_id  VARCHAR(36),
                            created_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                            CONSTRAINT fk_tx_account FOREIGN KEY (account_id) REFERENCES accounts (id),
                            CONSTRAINT chk_tx_type   CHECK (tx_type IN ('CREDIT', 'DEBIT')),
                            CONSTRAINT chk_tx_amount CHECK (amount > 0),
                            CONSTRAINT uq_tx_account_reference UNIQUE (account_id, reference_id)
                        )""");
                st.execute("CREATE TRIGGER IF NOT EXISTS trg_tx_immutable BEFORE UPDATE, DELETE ON transactions "
                        + "FOR EACH ROW CALL \"" + ImmutableTransactionTrigger.class.getName() + "\"");
                st.execute("CREATE INDEX IF NOT EXISTS idx_tx_account_seq ON transactions (account_id, seq DESC)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_tx_transfer ON transactions (transfer_id)");
            }
            return null;
        });
    }
}
