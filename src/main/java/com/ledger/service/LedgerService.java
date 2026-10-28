package com.ledger.service;

import com.ledger.db.AccountRepository;
import com.ledger.db.Database;
import com.ledger.db.TransactionRepository;
import com.ledger.exception.LedgerException;
import com.ledger.model.Account;
import com.ledger.model.Transaction;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class LedgerService {

    public static final int MAX_LIMIT = 100;
    private static final int MAX_NAME = 255;
    private static final int MAX_REFERENCE = 100;
    private static final int MAX_DESCRIPTION = 500;
    private static final int MAX_INTEGER_DIGITS = 17;

    public record Result(Transaction transaction, BigDecimal balance, boolean duplicate) {
    }

    public record Page(List<Transaction> data, int page, int limit, long totalRecords, long totalPages) {
    }

    public record TransferResult(String transferId, Transaction debit, Transaction credit,
                                 BigDecimal sourceBalance, BigDecimal destinationBalance, boolean duplicate) {
    }

    private final Database db;
    private final Clock clock;
    private final AccountRepository accounts = new AccountRepository();
    private final TransactionRepository transactions = new TransactionRepository();

    public LedgerService(Database db) {
        this(db, Clock.systemUTC());
    }

    public LedgerService(Database db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    public Account createAccount(String name) {
        if (name == null || name.isBlank()) {
            throw LedgerException.validation("name is required");
        }
        if (name.length() > MAX_NAME) {
            throw LedgerException.validation("name must be at most " + MAX_NAME + " characters");
        }
        Account account = new Account(UUID.randomUUID().toString(), name.trim(), BigDecimal.ZERO.setScale(2), now());
        return db.inTransaction(c -> {
            accounts.insert(c, account);
            return account;
        });
    }

    public Account getAccount(String accountId) {
        return db.inTransaction(c -> accounts.find(c, accountId)
                .orElseThrow(() -> LedgerException.accountNotFound("account not found: " + accountId)));
    }

    public BigDecimal getBalance(String accountId) {
        return getAccount(accountId).balance();
    }

    public Page getTransactions(String accountId, Transaction.Type type, int page, int limit) {
        if (page < 1) {
            throw LedgerException.validation("page must be >= 1");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw LedgerException.validation("limit must be between 1 and " + MAX_LIMIT);
        }
        return db.inTransaction(c -> {
            if (accounts.find(c, accountId).isEmpty()) {
                throw LedgerException.accountNotFound("account not found: " + accountId);
            }
            long total = transactions.count(c, accountId, type);
            long totalPages = (total + limit - 1) / limit;
            List<Transaction> data = transactions.page(c, accountId, type, limit, (long) (page - 1) * limit);
            return new Page(data, page, limit, total, totalPages);
        });
    }

    public Result addTransaction(String accountId, Transaction.Type type, BigDecimal amount,
                                 String description, String referenceId) {
        if (type == null) {
            throw LedgerException.validation("type must be 'credit' or 'debit'");
        }
        BigDecimal amt = validateAmount(amount);
        validateReference(referenceId);
        validateDescription(description);
        if (accountId == null || accountId.isBlank()) {
            throw LedgerException.validation("account id is required");
        }

        return db.inTransaction(c -> {
            BigDecimal balance = accounts.lockBalance(c, accountId)
                    .orElseThrow(() -> LedgerException.accountNotFound("account not found: " + accountId));

            Optional<Transaction> existing = transactions.findByReference(c, accountId, referenceId);
            if (existing.isPresent()) {
                return new Result(existing.get(), balance, true);
            }

            BigDecimal newBalance = apply(balance, type, amt);
            Transaction tx = new Transaction(UUID.randomUUID().toString(), accountId, type, amt,
                    description, referenceId, null, now());
            transactions.insert(c, tx);
            accounts.updateBalance(c, accountId, newBalance);
            return new Result(tx, newBalance, false);
        });
    }

    public TransferResult transfer(String fromId, String toId, BigDecimal amount,
                                   String description, String referenceId) {
        if (fromId == null || fromId.isBlank()) {
            throw LedgerException.validation("from_account_id is required");
        }
        if (toId == null || toId.isBlank()) {
            throw LedgerException.validation("to_account_id is required");
        }
        if (fromId.equals(toId)) {
            throw LedgerException.validation("from_account_id and to_account_id must differ");
        }
        BigDecimal amt = validateAmount(amount);
        validateReference(referenceId);
        validateDescription(description);

        return db.inTransaction(c -> {
            // lock in id order so opposite transfers cannot deadlock
            boolean fromFirst = fromId.compareTo(toId) < 0;
            Optional<BigDecimal> first = accounts.lockBalance(c, fromFirst ? fromId : toId);
            Optional<BigDecimal> second = accounts.lockBalance(c, fromFirst ? toId : fromId);
            BigDecimal fromBalance = (fromFirst ? first : second)
                    .orElseThrow(() -> LedgerException.accountNotFound("source account not found: " + fromId));
            BigDecimal toBalance = (fromFirst ? second : first)
                    .orElseThrow(() -> LedgerException.accountNotFound("destination account not found: " + toId));

            Optional<Transaction> existing = transactions.findByReference(c, fromId, referenceId);
            if (existing.isPresent()) {
                Transaction debit = existing.get();
                if (debit.transferId() == null || debit.type() != Transaction.Type.DEBIT) {
                    throw LedgerException.conflict("reference_id is already used by a different transaction");
                }
                Transaction credit = transactions
                        .findByTransferAndType(c, debit.transferId(), Transaction.Type.CREDIT).orElseThrow();
                return new TransferResult(debit.transferId(), debit, credit, fromBalance, toBalance, true);
            }

            BigDecimal newFrom = apply(fromBalance, Transaction.Type.DEBIT, amt);
            BigDecimal newTo = apply(toBalance, Transaction.Type.CREDIT, amt);
            String transferId = UUID.randomUUID().toString();
            Instant now = now();
            Transaction debit = new Transaction(UUID.randomUUID().toString(), fromId, Transaction.Type.DEBIT,
                    amt, description, referenceId, transferId, now);
            Transaction credit = new Transaction(UUID.randomUUID().toString(), toId, Transaction.Type.CREDIT,
                    amt, description, referenceId, transferId, now);
            transactions.insert(c, debit);
            transactions.insert(c, credit);
            accounts.updateBalance(c, fromId, newFrom);
            accounts.updateBalance(c, toId, newTo);
            return new TransferResult(transferId, debit, credit, newFrom, newTo, false);
        });
    }

    private static BigDecimal apply(BigDecimal balance, Transaction.Type type, BigDecimal amount) {
        if (type == Transaction.Type.CREDIT) {
            return balance.add(amount);
        }
        if (balance.compareTo(amount) < 0) {
            throw LedgerException.insufficientBalance(
                    "insufficient balance: balance=" + balance.toPlainString() + ", debit=" + amount.toPlainString());
        }
        return balance.subtract(amount);
    }

    private static BigDecimal validateAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw LedgerException.validation("amount must be greater than 0");
        }
        BigDecimal stripped = amount.stripTrailingZeros();
        if (stripped.scale() > 2) {
            throw LedgerException.validation("amount supports at most 2 decimal places");
        }
        if (stripped.precision() - stripped.scale() > MAX_INTEGER_DIGITS) {
            throw LedgerException.validation("amount is too large");
        }
        return stripped.setScale(2);
    }

    private static void validateReference(String referenceId) {
        if (referenceId == null || referenceId.isBlank()) {
            throw LedgerException.validation("reference_id is required");
        }
        if (referenceId.length() > MAX_REFERENCE) {
            throw LedgerException.validation("reference_id must be at most " + MAX_REFERENCE + " characters");
        }
    }

    private static void validateDescription(String description) {
        if (description != null && description.length() > MAX_DESCRIPTION) {
            throw LedgerException.validation("description must be at most " + MAX_DESCRIPTION + " characters");
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
