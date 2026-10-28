package com.ledger;

import static org.junit.jupiter.api.Assertions.*;

import com.ledger.db.Database;
import com.ledger.exception.LedgerException;
import com.ledger.model.Account;
import com.ledger.model.Transaction;
import com.ledger.service.LedgerService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class LedgerServiceTest {
    private final Database db = Database.inMemory();
    private final LedgerService ledger = new LedgerService(db);

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    private void assertBalance(String expected, String accountId) {
        assertEquals(0, bd(expected).compareTo(ledger.getBalance(accountId)), "balance of " + accountId);
    }

    private static List<String> refs(LedgerService.Page p) {
        return p.data().stream().map(Transaction::referenceId).toList();
    }

    private Account fund(String name, String amount) {
        Account a = ledger.createAccount(name);
        ledger.addTransaction(a.id(), Transaction.Type.CREDIT, bd(amount), "seed", "seed");
        return a;
    }

    @Test
    void newAccountHasZeroBalance() {
        Account a = ledger.createAccount("Alice");
        assertEquals(0, a.balance().signum());
        assertNotNull(a.createdAt());
        assertEquals("VALIDATION_ERROR", assertThrows(LedgerException.class, () -> ledger.createAccount(" ")).code());
    }

    @Test
    void creditAndDebitAdjustBalance() {
        Account a = ledger.createAccount("Alice");
        ledger.addTransaction(a.id(), Transaction.Type.CREDIT, bd("100"), "d", "r1");
        ledger.addTransaction(a.id(), Transaction.Type.CREDIT, bd("50"), "d", "r2");
        ledger.addTransaction(a.id(), Transaction.Type.DEBIT, bd("30"), "d", "r3");
        assertBalance("120", a.id());
        assertEquals(3L, ledger.getTransactions(a.id(), null, 1, 100).totalRecords());
    }

    @Test
    void debitWithInsufficientBalanceIsRejectedAndNothingPersisted() {
        Account a = fund("Alice", "100");
        LedgerException e = assertThrows(LedgerException.class,
                () -> ledger.addTransaction(a.id(), Transaction.Type.DEBIT, bd("150"), "d", "r2"));
        assertEquals(422, e.status());
        assertEquals("INSUFFICIENT_BALANCE", e.code());
        assertBalance("100", a.id());
        assertEquals(1L, ledger.getTransactions(a.id(), null, 1, 100).totalRecords());
    }

    @Test
    void duplicateReferenceIsIdempotentPerAccount() {
        Account a = ledger.createAccount("A");
        Account b = ledger.createAccount("B");
        var first = ledger.addTransaction(a.id(), Transaction.Type.CREDIT, bd("100"), "d", "ref");
        var again = ledger.addTransaction(a.id(), Transaction.Type.CREDIT, bd("100"), "d", "ref");
        assertFalse(first.duplicate());
        assertTrue(again.duplicate());
        assertEquals(first.transaction().id(), again.transaction().id());
        assertBalance("100", a.id());

        assertFalse(ledger.addTransaction(b.id(), Transaction.Type.CREDIT, bd("5"), "d", "ref").duplicate());
    }

    @Test
    void validatesInput() {
        Account a = ledger.createAccount("A");
        for (String bad : new String[]{"0", "-1", "1.234"}) {
            assertEquals(400, assertThrows(LedgerException.class,
                    () -> ledger.addTransaction(a.id(), Transaction.Type.CREDIT, bd(bad), "d", "r")).status(), bad);
        }
        assertThrows(LedgerException.class, () -> ledger.addTransaction(a.id(), Transaction.Type.CREDIT, bd("1"), "d", " "));
        assertThrows(LedgerException.class, () -> ledger.addTransaction(a.id(), null, bd("1"), "d", "r"));
        assertThrows(LedgerException.class, () -> ledger.addTransaction(a.id(), Transaction.Type.CREDIT, bd("1e30"), "d", "r"));
        assertEquals("ACCOUNT_NOT_FOUND", assertThrows(LedgerException.class,
                () -> ledger.addTransaction("nope", Transaction.Type.CREDIT, bd("1"), "d", "r")).code());
    }

    @Test
    void historyIsFilteredPaginatedAndLatestFirst() {
        Account a = ledger.createAccount("A");
        for (int i = 1; i <= 5; i++) {
            ledger.addTransaction(a.id(), Transaction.Type.CREDIT, bd("100"), "c", "c" + i);
        }
        for (int i = 1; i <= 3; i++) {
            ledger.addTransaction(a.id(), Transaction.Type.DEBIT, bd("1"), "d", "d" + i);
        }

        var all = ledger.getTransactions(a.id(), null, 1, 3);
        assertEquals(8, all.totalRecords());
        assertEquals(3, all.totalPages());
        assertEquals(List.of("d3", "d2", "d1"), refs(all));
        assertEquals(List.of("c2", "c1"), refs(ledger.getTransactions(a.id(), null, 3, 3)));

        var credits = ledger.getTransactions(a.id(), Transaction.Type.CREDIT, 2, 2);
        assertEquals(5, credits.totalRecords());
        assertEquals(3, credits.totalPages());
        assertEquals(List.of("c3", "c2"), refs(credits));

        assertTrue(ledger.getTransactions(a.id(), null, 99, 3).data().isEmpty());
        assertEquals(400, assertThrows(LedgerException.class, () -> ledger.getTransactions(a.id(), null, 0, 3)).status());
        assertEquals(400, assertThrows(LedgerException.class, () -> ledger.getTransactions(a.id(), null, 1, 101)).status());
        assertEquals(404, assertThrows(LedgerException.class, () -> ledger.getTransactions("nope", null, 1, 3)).status());
        assertEquals(404, assertThrows(LedgerException.class, () -> ledger.getBalance("nope")).status());
    }

    @Test
    void transferMovesMoney() {
        Account a = fund("A", "100");
        Account b = fund("B", "50");
        var res = ledger.transfer(a.id(), b.id(), bd("30"), "rent", "t1");
        assertFalse(res.duplicate());
        assertBalance("70", a.id());
        assertBalance("80", b.id());
        assertEquals(res.transferId(), res.debit().transferId());
        assertEquals(res.transferId(), res.credit().transferId());
    }

    @Test
    void transferIsIdempotentOnReference() {
        Account a = fund("A", "100");
        Account b = fund("B", "50");
        var first = ledger.transfer(a.id(), b.id(), bd("30"), "x", "t1");
        var again = ledger.transfer(a.id(), b.id(), bd("30"), "x", "t1");
        assertTrue(again.duplicate());
        assertEquals(first.transferId(), again.transferId());
        assertBalance("70", a.id());
        assertBalance("80", b.id());
    }

    @Test
    void transferRejectedWhenInsufficientOrAccountMissingOrSame() {
        Account a = fund("A", "100");
        Account b = fund("B", "50");
        assertEquals(422, assertThrows(LedgerException.class,
                () -> ledger.transfer(a.id(), b.id(), bd("101"), "x", "t1")).status());
        assertEquals(404, assertThrows(LedgerException.class,
                () -> ledger.transfer(a.id(), "ghost", bd("1"), "x", "t2")).status());
        assertEquals(404, assertThrows(LedgerException.class,
                () -> ledger.transfer("ghost", b.id(), bd("1"), "x", "t3")).status());
        assertEquals(400, assertThrows(LedgerException.class,
                () -> ledger.transfer(a.id(), a.id(), bd("1"), "x", "t4")).status());
        assertBalance("100", a.id());
        assertBalance("50", b.id());
        assertEquals(1L, ledger.getTransactions(a.id(), null, 1, 10).totalRecords());
    }

    @Test
    void transferFailingMidwayRollsBackEverything() {
        Account a = fund("A", "100");
        Account b = ledger.createAccount("B");
        // B already has reference "t1", so the credit leg fails after the debit was written
        ledger.addTransaction(b.id(), Transaction.Type.CREDIT, bd("5"), "x", "t1");

        LedgerException e = assertThrows(LedgerException.class,
                () -> ledger.transfer(a.id(), b.id(), bd("30"), "x", "t1"));
        assertEquals(409, e.status());
        assertBalance("100", a.id());
        assertBalance("5", b.id());
        assertEquals(1L, ledger.getTransactions(a.id(), null, 1, 10).totalRecords());
        assertEquals(1L, ledger.getTransactions(b.id(), null, 1, 10).totalRecords());
    }

    @Test
    void failedUnitOfWorkLeavesNoTrace() {
        assertThrows(IllegalStateException.class, () -> db.inTransaction(c -> {
            c.createStatement().executeUpdate(
                    "INSERT INTO accounts (id, name, balance, created_at) VALUES ('x','n',0,CURRENT_TIMESTAMP)");
            throw new IllegalStateException("boom");
        }));
        assertEquals(404, assertThrows(LedgerException.class, () -> ledger.getAccount("x")).status());
    }

    @Test
    void concurrentDebitsNeverOverdraw() throws Exception {
        Account a = fund("A", "100");
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<Boolean>> fs = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            String ref = "d" + i;
            fs.add(pool.submit(() -> {
                try {
                    ledger.addTransaction(a.id(), Transaction.Type.DEBIT, bd("10"), "d", ref);
                    return true;
                } catch (LedgerException e) {
                    return false;
                }
            }));
        }
        long ok = 0;
        for (var f : fs) if (f.get()) ok++;
        pool.shutdown();
        assertEquals(10, ok);
        assertBalance("0", a.id());
    }

    @Test
    void concurrentDuplicatesApplyOnce() throws Exception {
        Account a = ledger.createAccount("A");
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            fs.add(pool.submit(() -> ledger.addTransaction(a.id(), Transaction.Type.CREDIT, bd("10"), "d", "same")));
        }
        for (var f : fs) f.get();
        pool.shutdown();
        assertBalance("10", a.id());
        assertEquals(1L, ledger.getTransactions(a.id(), null, 1, 10).totalRecords());
    }

    @Test
    void opposingConcurrentTransfersDoNotDeadlockAndConserveMoney() throws Exception {
        Account a = fund("A", "1000");
        Account b = fund("B", "1000");
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            String ref = "t" + i;
            boolean forward = i % 2 == 0;
            fs.add(pool.submit(() -> ledger.transfer(forward ? a.id() : b.id(), forward ? b.id() : a.id(),
                    bd("5"), "x", ref)));
        }
        for (var f : fs) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertEquals(0, bd("2000").compareTo(ledger.getBalance(a.id()).add(ledger.getBalance(b.id()))));
        assertBalance("1000", a.id());
    }

    private BigDecimal replayBalance(String accountId) {
        return db.inTransaction(c -> {
            try (var ps = c.prepareStatement("SELECT COALESCE(SUM(CASE WHEN tx_type = 'CREDIT' "
                    + "THEN amount ELSE -amount END), 0) FROM transactions WHERE account_id = ?")) {
                ps.setString(1, accountId);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getBigDecimal(1);
                }
            }
        });
    }

    @Test
    void balanceAlwaysEqualsReplayOfValidTransactions() {
        Account a = ledger.createAccount("A");
        ledger.addTransaction(a.id(), Transaction.Type.CREDIT, bd("100"), "d", "1");
        ledger.addTransaction(a.id(), Transaction.Type.CREDIT, bd("50"), "d", "2");
        ledger.addTransaction(a.id(), Transaction.Type.DEBIT, bd("30"), "d", "3");
        ledger.addTransaction(a.id(), Transaction.Type.DEBIT, bd("20"), "d", "4");
        assertBalance("100", a.id());

        assertThrows(LedgerException.class, () -> ledger.addTransaction(a.id(), Transaction.Type.DEBIT, bd("500"), "d", "5"));
        assertThrows(LedgerException.class, () -> ledger.addTransaction(a.id(), Transaction.Type.CREDIT, bd("-1"), "d", "6"));
        ledger.addTransaction(a.id(), Transaction.Type.CREDIT, bd("100"), "d", "1");

        assertBalance("100", a.id());
        assertEquals(0, ledger.getBalance(a.id()).compareTo(replayBalance(a.id())));
    }

    @Test
    void transactionsCannotBeUpdatedOrDeletedEvenViaSql() {
        Account a = fund("A", "100");
        for (String sql : new String[]{
                "UPDATE transactions SET amount = 1", "UPDATE transactions SET description = 'x'",
                "UPDATE transactions SET reference_id = 'x'", "DELETE FROM transactions"}) {
            assertThrows(IllegalStateException.class, () -> db.inTransaction(c -> c.createStatement().executeUpdate(sql)), sql);
        }
        var tx = ledger.getTransactions(a.id(), null, 1, 10).data().get(0);
        assertEquals(0, bd("100").compareTo(tx.amount()));
        assertEquals("seed", tx.referenceId());
    }
}
