package com.ledger.model;

import java.math.BigDecimal;
import java.time.Instant;

public record Transaction(
        String id,
        String accountId,
        Type type,
        BigDecimal amount,
        String description,
        String referenceId,
        String transferId,
        Instant createdAt) {

    public enum Type { CREDIT, DEBIT }
}
