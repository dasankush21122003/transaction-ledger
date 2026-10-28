package com.ledger.db;

import com.ledger.model.Account;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

public class AccountRepository {

    public void insert(Connection c, Account a) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO accounts (id, name, balance, created_at) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, a.id());
            ps.setString(2, a.name());
            ps.setBigDecimal(3, a.balance());
            ps.setObject(4, OffsetDateTime.ofInstant(a.createdAt(), ZoneOffset.UTC));
            ps.executeUpdate();
        }
    }

    public Optional<Account> find(Connection c, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, name, balance, created_at FROM accounts WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Account(rs.getString("id"), rs.getString("name"),
                        rs.getBigDecimal("balance"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()));
            }
        }
    }

    public Optional<BigDecimal> lockBalance(Connection c, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT balance FROM accounts WHERE id = ? FOR UPDATE")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getBigDecimal(1)) : Optional.empty();
            }
        }
    }

    public void updateBalance(Connection c, String id, BigDecimal balance) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE accounts SET balance = ? WHERE id = ?")) {
            ps.setBigDecimal(1, balance);
            ps.setString(2, id);
            ps.executeUpdate();
        }
    }
}
