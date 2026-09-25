package com.example.hanresstest;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Real initialize -> initialized -> tools/call over Streamable HTTP, no model required. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "mcp.security.service-token=test-mcp-service-token-1234567890-abcd",
        "order-service.service-token=test-oms-secret",
        "execution-service.service-token=test-execution-service-secret-1234567890",
        "order-service.status-path=/orders/{orderId}/status",
        "order-service.cancel-path=/orders/{orderId}/cancel"
})
class McpTransportTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final AtomicBoolean APPROVED = new AtomicBoolean();
    private static final AtomicInteger WRITES = new AtomicInteger();
    private static final List<String> KEYS = new CopyOnWriteArrayList<>();
    private static final List<String> IDENTITIES = new CopyOnWriteArrayList<>();
    private static final List<String> CONVERSATIONS = new CopyOnWriteArrayList<>();
    private static final String EXECUTION = UUID.randomUUID().toString();
    private static final String APPROVAL = UUID.randomUUID().toString();
    private static final HttpServer UPSTREAM = upstream();
    @Value("${local.server.port}") private int port;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private static HttpServer upstream() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/orders/1001/status", exchange -> {
                IDENTITIES.add(exchange.getRequestHeaders().getFirst("X-Tenant-Id"));
                byte[] body = "{\"orderId\":\"1001\",\"status\":\"PROCESSING\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.createContext("/api/v1/internal/executions/prepare", exchange -> {
                var request = JSON.readTree(exchange.getRequestBody().readAllBytes());
                CONVERSATIONS.add(request.get("conversation_id").asText());
                String id = APPROVED.get() ? "\"" + EXECUTION + "\"" : "null";
                byte[] body = ("{\"status\":\"" + (APPROVED.get() ? "AUTHORIZED" : "PENDING")
                        + "\",\"approval_id\":\"" + APPROVAL + "\",\"execution_id\":" + id
                        + ",\"expires_at\":\"2099-01-01T00:00:00Z\"}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.createContext("/orders/1001/cancel", exchange -> {
                WRITES.incrementAndGet();
                KEYS.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                byte[] body = "{\"status\":\"CANCELLED\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("order-service.base-url", () -> "http://127.0.0.1:" + UPSTREAM.getAddress().getPort());
        registry.add("execution-service.base-url", () -> "http://127.0.0.1:" + UPSTREAM.getAddress().getPort());
    }
    @AfterAll static void stop() { UPSTREAM.stop(0); }

    private HttpResponse<String> send(Object body, String session, String cid, String tenant) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Authorization", "Bearer test-mcp-service-token-1234567890-abcd")
                .header("X-User-Id", "user").header("X-Tenant-Id", tenant).header("X-Conversation-Id", cid);
        if (session != null) builder.header("Mcp-Session-Id", session).header("MCP-Protocol-Version", "2025-03-26");
        return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode rpc(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        String body = response.body();
        if (body.stripLeading().startsWith("{")) return JSON.readTree(body);
        String data = body.lines().filter(line -> line.startsWith("data:")).map(line -> line.substring(5).strip()).findFirst().orElseThrow();
        return JSON.readTree(data);
    }

    private String session(String cid, String tenant) throws Exception {
        var response = send(Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize", "params", Map.of(
                "protocolVersion", "2025-03-26", "capabilities", Map.of(), "clientInfo", Map.of("name", "transport-test", "version", "1"))), null, cid, tenant);
        assertNotNull(rpc(response).get("result"));
        String id = response.headers().firstValue("Mcp-Session-Id").orElseThrow();
        var notified = send(Map.of("jsonrpc", "2.0", "method", "notifications/initialized"), id, cid, tenant);
        assertTrue(notified.statusCode() < 300, notified.body());
        return id;
    }

    private JsonNode call(String tool, String session, String cid, String tenant) throws Exception {
        var response = rpc(send(Map.of("jsonrpc", "2.0", "id", UUID.randomUUID().toString(), "method", "tools/call",
                "params", Map.of("name", tool, "arguments", Map.of("orderId", "1001"))), session, cid, tenant));
        assertNull(response.get("error"), response.toString());
        var result = response.get("result");
        assertFalse(result.path("isError").asBoolean(false), result.toString());
        return JSON.readTree(result.get("content").get(0).get("text").asText());
    }

    @Test
    void realTransportPreservesIdentityApprovalGateAndRetryId() throws Exception {
        String cid = UUID.randomUUID().toString();
        String sid = session(cid, "tenant-a");
        assertEquals("PROCESSING", call("get_order_status", sid, cid, "tenant-a").get("status").asText());
        assertEquals("PENDING", call("cancel_order", sid, cid, "tenant-a").get("status").asText());
        assertEquals(0, WRITES.get());
        APPROVED.set(true);
        assertEquals("CANCELLED", call("cancel_order", sid, cid, "tenant-a").get("status").asText());
        call("cancel_order", sid, cid, "tenant-a");
        assertEquals(List.of(EXECUTION, EXECUTION), KEYS);
        assertEquals(List.of(cid, cid, cid), CONVERSATIONS);
        String otherCid = UUID.randomUUID().toString();
        call("get_order_status", session(otherCid, "tenant-b"), otherCid, "tenant-b");
        assertEquals(List.of("tenant-a", "tenant-b"), IDENTITIES);
    }
}
