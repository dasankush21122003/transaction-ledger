package com.ledger.exception;

public class LedgerException extends RuntimeException {
    private final int status;
    private final String code;

    public LedgerException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static LedgerException validation(String msg) { return new LedgerException(400, "VALIDATION_ERROR", msg); }
    public static LedgerException invalidBody(String msg) { return new LedgerException(400, "INVALID_REQUEST_BODY", msg); }
    public static LedgerException accountNotFound(String msg) { return new LedgerException(404, "ACCOUNT_NOT_FOUND", msg); }
    public static LedgerException notFound(String msg) { return new LedgerException(404, "NOT_FOUND", msg); }
    public static LedgerException methodNotAllowed() { return new LedgerException(405, "METHOD_NOT_ALLOWED", "method not allowed"); }
    public static LedgerException conflict(String msg) { return new LedgerException(409, "CONFLICT", msg); }
    public static LedgerException insufficientBalance(String msg) { return new LedgerException(422, "INSUFFICIENT_BALANCE", msg); }
}
