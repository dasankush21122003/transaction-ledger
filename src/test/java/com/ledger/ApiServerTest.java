package com.ledger;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledger.api.ApiServer;
import com.ledger.db.Database;
import com.ledger.service.LedgerService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApiServerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private ApiServer server;
    private String base;

    @BeforeEach
    void start() throws Exception {
        server = new ApiServer(new LedgerService(Database.inMemory()), 0);
        server.start();
        base = "http://localhost:" + server.port();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    private record Resp(int status, JsonNode body) {}

    private Resp call(String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path));
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(r.statusCode(), JSON.readTree(r.body()));
    }

    private String newAccount() throws Exception {
        return call("POST", "/accounts", "{\"name\":\"Alice\"}").body().get("id").asText();
    }

    private void assertError(Resp r, int status, String code) {
        assertEquals(status, r.status(), r.body().toString());
        assertEquals(code, r.body().at("/error/code").asText());
        assertFalse(r.body().at("/error/message").asText().isEmpty());
    }

    @Test
    void createAccountRules() throws Exception {
        Resp ok = call("POST", "/accounts", "{\"name\":\"Alice\"}");
        assertEquals(201, ok.status());
        assertEquals(0, ok.body().get("balance").decimalValue().signum());
        assertTrue(ok.body().has("created_at"));

        assertError(call("POST", "/accounts", "{\"name\":\"Alice\",\"balance\":100}"), 400, "VALIDATION_ERROR");
        assertError(call("POST", "/accounts", "{}"), 400, "VALIDATION_ERROR");
        assertError(call("POST", "/accounts", "{\"name\":\"  \"}"), 400, "VALIDATION_ERROR");
        assertError(call("POST", "/accounts", "{\"name\":42}"), 400, "VALIDATION_ERROR");
        assertError(call("POST", "/accounts", "not json"), 400, "INVALID_REQUEST_BODY");
        assertError(call("POST", "/accounts", ""), 400, "INVALID_REQUEST_BODY");
        assertError(call("POST", "/accounts", "[1]"), 400, "INVALID_REQUEST_BODY");
    }

    @Test
    void transactionFlowAndErrors() throws Exception {
        String id = newAccount();
        String tx = "/accounts/" + id + "/transactions";
        String credit = "{\"type\":\"credit\",\"amount\":100,\"description\":\"d\",\"reference_id\":\"r1\"}";

        Resp created = call("POST", tx, credit);
        assertEquals(201, created.status());
        assertEquals("CREATED", created.body().get("status").asText());

        Resp dup = call("POST", tx, credit);
        assertEquals(200, dup.status());
        assertEquals("ALREADY_EXISTS", dup.body().get("status").asText());
        assertEquals(100, call("GET", "/accounts/" + id + "/balance", null).body().get("balance").asInt());

        assertError(call("POST", tx, "{\"type\":\"debit\",\"amount\":150,\"reference_id\":\"r2\"}"), 422, "INSUFFICIENT_BALANCE");
        assertError(call("POST", tx, "{\"type\":\"refund\",\"amount\":1,\"reference_id\":\"r3\"}"), 400, "VALIDATION_ERROR");
        assertError(call("POST", tx, "{\"type\":\"credit\",\"amount\":-5,\"reference_id\":\"r3\"}"), 400, "VALIDATION_ERROR");
        assertError(call("POST", tx, "{\"type\":\"credit\",\"amount\":\"5\",\"reference_id\":\"r3\"}"), 400, "VALIDATION_ERROR");
        assertError(call("POST", tx, "{\"type\":\"credit\",\"amount\":1.005,\"reference_id\":\"r3\"}"), 400, "VALIDATION_ERROR");
        assertError(call("POST", tx, "{\"type\":\"credit\",\"amount\":5}"), 400, "VALIDATION_ERROR");
        assertError(call("POST", "/accounts/ghost/transactions", credit), 404, "ACCOUNT_NOT_FOUND");
        assertError(call("GET", "/accounts/ghost/balance", null), 404, "ACCOUNT_NOT_FOUND");
        assertError(call("GET", "/accounts/ghost", null), 404, "ACCOUNT_NOT_FOUND");
        assertError(call("DELETE", "/accounts/" + id, null), 405, "METHOD_NOT_ALLOWED");
        assertError(call("GET", "/nope", null), 404, "NOT_FOUND");
    }

    @Test
    void historyPaginationParams() throws Exception {
        String id = newAccount();
        for (int i = 0; i < 3; i++) {
            call("POST", "/accounts/" + id + "/transactions",
                    "{\"type\":\"credit\",\"amount\":1,\"reference_id\":\"r" + i + "\"}");
        }
        Resp r = call("GET", "/accounts/" + id + "/transactions?type=CREDIT&page=2&limit=2", null);
        assertEquals(200, r.status());
        assertEquals(1, r.body().get("data").size());
        assertEquals(3, r.body().get("total_records").asInt());
        assertEquals(2, r.body().get("total_pages").asInt());
        assertEquals(2, r.body().get("page").asInt());
        assertEquals(2, r.body().get("limit").asInt());

        for (String q : new String[]{"page=0", "page=abc", "limit=0", "limit=1000", "type=x"}) {
            assertError(call("GET", "/accounts/" + id + "/transactions?" + q, null), 400, "VALIDATION_ERROR");
        }
    }

    @Test
    void transferEndpoint() throws Exception {
        String a = newAccount();
        String b = newAccount();
        call("POST", "/accounts/" + a + "/transactions", "{\"type\":\"credit\",\"amount\":100,\"reference_id\":\"s\"}");
        call("POST", "/accounts/" + b + "/transactions", "{\"type\":\"credit\",\"amount\":50,\"reference_id\":\"s\"}");

        String body = "{\"from_account_id\":\"" + a + "\",\"to_account_id\":\"" + b + "\",\"amount\":30,\"reference_id\":\"t1\"}";
        Resp r = call("POST", "/transfers", body);
        assertEquals(201, r.status(), r.body().toString());
        assertEquals(70, r.body().get("source_balance").asInt());
        assertEquals(80, r.body().get("destination_balance").asInt());
        assertEquals(200, call("POST", "/transfers", body).status());
        assertEquals(70, call("GET", "/accounts/" + a + "/balance", null).body().get("balance").asInt());

        assertError(call("POST", "/transfers", body.replace("\"amount\":30", "\"amount\":999").replace("t1", "t2")), 422, "INSUFFICIENT_BALANCE");
        assertError(call("POST", "/transfers", body.replace(b, "ghost").replace("t1", "t3")), 404, "ACCOUNT_NOT_FOUND");
        assertError(call("POST", "/transfers", "{\"from_account_id\":\"" + a + "\"}"), 400, "VALIDATION_ERROR");
    }

    @Test
    void transactionsAreImmutableThroughTheApi() throws Exception {
        String id = newAccount();
        Resp created = call("POST", "/accounts/" + id + "/transactions",
                "{\"type\":\"credit\",\"amount\":10,\"reference_id\":\"r1\"}");
        String path = "/accounts/" + id + "/transactions/" + created.body().at("/transaction/id").asText();
        for (String m : new String[]{"PUT", "PATCH", "DELETE", "POST"}) {
            assertError(call(m, path, "{\"amount\":1}"), 405, "METHOD_NOT_ALLOWED");
        }
        assertError(call("DELETE", "/accounts/" + id + "/transactions", null), 405, "METHOD_NOT_ALLOWED");
        assertEquals(10, call("GET", "/accounts/" + id + "/balance", null).body().get("balance").asInt());
    }
}
