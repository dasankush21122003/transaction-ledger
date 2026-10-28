package com.ledger.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ledger.exception.LedgerException;
import com.ledger.model.Transaction;
import com.ledger.service.LedgerService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ApiServer {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    private final LedgerService ledger;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newFixedThreadPool(16);

    public ApiServer(LedgerService ledger, int port) throws IOException {
        this.ledger = ledger;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.server.createContext("/", this::handle);
        this.server.setExecutor(executor);
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
        executor.shutdown();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            route(ex);
        } catch (LedgerException e) {
            sendError(ex, e.status(), e.code(), e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            sendError(ex, 500, "INTERNAL_ERROR", "internal server error");
        } finally {
            ex.close();
        }
    }

    private void route(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath().replaceAll("^/+|/+$", "");
        String[] p = path.isEmpty() ? new String[0] : path.split("/");
        String method = ex.getRequestMethod();

        if (p.length == 1 && p[0].equals("accounts")) {
            requireMethod(method, "POST");
            createAccount(ex);
        } else if (p.length == 2 && p[0].equals("accounts")) {
            requireMethod(method, "GET");
            send(ex, 200, ledger.getAccount(p[1]));
        } else if (p.length == 3 && p[0].equals("accounts") && p[2].equals("balance")) {
            requireMethod(method, "GET");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("account_id", p[1]);
            out.put("balance", ledger.getBalance(p[1]));
            send(ex, 200, out);
        } else if (p.length == 3 && p[0].equals("accounts") && p[2].equals("transactions")) {
            if (method.equals("POST")) {
                addTransaction(ex, p[1]);
            } else {
                requireMethod(method, "GET");
                listTransactions(ex, p[1]);
            }
        } else if (p.length == 4 && p[0].equals("accounts") && p[2].equals("transactions")) {
            if (method.equals("GET")) {
                throw LedgerException.notFound("no such endpoint");
            }
            throw new LedgerException(405, "METHOD_NOT_ALLOWED", "transactions are immutable and cannot be modified or deleted");
        } else if (p.length == 1 && p[0].equals("transfers")) {
            requireMethod(method, "POST");
            transfer(ex);
        } else {
            throw LedgerException.notFound("no such endpoint");
        }
    }

    private static void requireMethod(String actual, String expected) {
        if (!actual.equals(expected)) {
            throw LedgerException.methodNotAllowed();
        }
    }

    private void createAccount(HttpExchange ex) throws IOException {
        JsonNode body = readJson(ex);
        for (String forbidden : List.of("balance", "id", "created_at")) {
            if (body.has(forbidden)) {
                throw LedgerException.validation(forbidden + " cannot be set by the client");
            }
        }
        send(ex, 201, ledger.createAccount(text(body, "name", true)));
    }

    private void addTransaction(HttpExchange ex, String accountId) throws IOException {
        JsonNode body = readJson(ex);
        Transaction.Type type = parseType(text(body, "type", true));
        BigDecimal amount = amount(body);
        String description = text(body, "description", false);
        String ref = text(body, "reference_id", true);

        LedgerService.Result res = ledger.addTransaction(accountId, type, amount, description, ref);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", res.duplicate() ? "ALREADY_EXISTS" : "CREATED");
        out.put("transaction", res.transaction());
        out.put("balance", res.balance());
        send(ex, res.duplicate() ? 200 : 201, out);
    }

    private void transfer(HttpExchange ex) throws IOException {
        JsonNode body = readJson(ex);
        String from = text(body, "from_account_id", true);
        String to = text(body, "to_account_id", true);
        BigDecimal amount = amount(body);
        String description = text(body, "description", false);
        String ref = text(body, "reference_id", true);

        LedgerService.TransferResult res = ledger.transfer(from, to, amount, description, ref);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", res.duplicate() ? "ALREADY_EXISTS" : "CREATED");
        out.put("transfer_id", res.transferId());
        out.put("debit", res.debit());
        out.put("credit", res.credit());
        out.put("source_balance", res.sourceBalance());
        out.put("destination_balance", res.destinationBalance());
        send(ex, res.duplicate() ? 200 : 201, out);
    }

    private void listTransactions(HttpExchange ex, String accountId) throws IOException {
        Map<String, String> q = queryParams(ex);
        Transaction.Type type = q.containsKey("type") ? parseType(q.get("type")) : null;
        int page = intParam(q, "page", 1);
        int limit = intParam(q, "limit", 20);

        LedgerService.Page result = ledger.getTransactions(accountId, type, page, limit);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("data", result.data());
        out.put("page", result.page());
        out.put("limit", result.limit());
        out.put("total_records", result.totalRecords());
        out.put("total_pages", result.totalPages());
        send(ex, 200, out);
    }

    private static Transaction.Type parseType(String value) {
        if (value != null) {
            try {
                return Transaction.Type.valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // falls through to the error below
            }
        }
        throw LedgerException.validation("type must be 'credit' or 'debit'");
    }

    private static BigDecimal amount(JsonNode body) {
        JsonNode a = body.get("amount");
        if (a == null || a.isNull()) {
            throw LedgerException.validation("amount is required");
        }
        if (!a.isNumber()) {
            throw LedgerException.validation("amount must be a number");
        }
        return a.decimalValue();
    }

    private static String text(JsonNode body, String field, boolean required) {
        JsonNode n = body.get(field);
        if (n == null || n.isNull()) {
            if (required) {
                throw LedgerException.validation(field + " is required");
            }
            return null;
        }
        if (!n.isTextual()) {
            throw LedgerException.validation(field + " must be a string");
        }
        return n.asText();
    }

    private JsonNode readJson(HttpExchange ex) throws IOException {
        try {
            JsonNode n = MAPPER.readTree(ex.getRequestBody());
            if (n == null || !n.isObject()) {
                throw LedgerException.invalidBody("request body must be a JSON object");
            }
            return n;
        } catch (JsonProcessingException e) {
            throw LedgerException.invalidBody("request body is not valid JSON");
        }
    }

    private static Map<String, String> queryParams(HttpExchange ex) {
        Map<String, String> params = new LinkedHashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return params;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            params.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return params;
    }

    private static int intParam(Map<String, String> q, String name, int def) {
        String v = q.get(name);
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw LedgerException.validation(name + " must be an integer");
        }
    }

    private void sendError(HttpExchange ex, int status, String code, String message) throws IOException {
        send(ex, status, Map.of("error", Map.of("code", code, "message", message)));
    }

    private void send(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = MAPPER.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (var os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
