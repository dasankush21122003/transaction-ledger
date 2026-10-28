package com.ledger.model;

import java.math.BigDecimal;
import java.time.Instant;

public record Account(String id, String name, BigDecimal balance, Instant createdAt) {
}
