package com.ledger.db;

import com.ledger.model.Transaction;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class TransactionRepository {

    private static final String COLUMNS =
            "id, account_id, tx_type, amount, description, reference_id, transfer_id, created_at";

    public void insert(Connection c, Transaction t) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO transactions (" + COLUMNS
                + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, t.id());
            ps.setString(2, t.accountId());
            ps.setString(3, t.type().name());
            ps.setBigDecimal(4, t.amount());
            ps.setString(5, t.description());
            ps.setString(6, t.referenceId());
            ps.setString(7, t.transferId());
            ps.setObject(8, OffsetDateTime.ofInstant(t.createdAt(), ZoneOffset.UTC));
            ps.executeUpdate();
        }
    }

    public Optional<Transaction> findByReference(Connection c, String accountId, String referenceId)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT " + COLUMNS + " FROM transactions WHERE account_id = ? AND reference_id = ?")) {
            ps.setString(1, accountId);
            ps.setString(2, referenceId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public Optional<Transaction> findByTransferAndType(Connection c, String transferId, Transaction.Type type)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT " + COLUMNS + " FROM transactions WHERE transfer_id = ? AND tx_type = ?")) {
            ps.setString(1, transferId);
            ps.setString(2, type.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public long count(Connection c, String accountId, Transaction.Type type) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM transactions WHERE account_id = ?" + typeFilter(type))) {
            ps.setString(1, accountId);
            if (type != null) {
                ps.setString(2, type.name());
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public List<Transaction> page(Connection c, String accountId, Transaction.Type type, int limit, long offset)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS
                + " FROM transactions WHERE account_id = ?" + typeFilter(type)
                + " ORDER BY created_at DESC, seq DESC LIMIT ? OFFSET ?")) {
            int i = 1;
            ps.setString(i++, accountId);
            if (type != null) {
                ps.setString(i++, type.name());
            }
            ps.setInt(i++, limit);
            ps.setLong(i, offset);
            List<Transaction> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(map(rs));
                }
            }
            return out;
        }
    }

    private static String typeFilter(Transaction.Type type) {
        return type == null ? "" : " AND tx_type = ?";
    }

    private static Transaction map(ResultSet rs) throws SQLException {
        return new Transaction(
                rs.getString("id"),
                rs.getString("account_id"),
                Transaction.Type.valueOf(rs.getString("tx_type")),
                rs.getBigDecimal("amount"),
                rs.getString("description"),
                rs.getString("reference_id"),
                rs.getString("transfer_id"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
