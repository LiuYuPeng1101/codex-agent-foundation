package com.example.hanresstest;

import com.example.hanresstest.config.*;
import com.example.hanresstest.execution.HttpExecutionAuthorizer;
import com.example.hanresstest.gateway.HttpOrderGateway;
import com.example.hanresstest.security.BusinessIdentity;
import com.example.hanresstest.service.OrderService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionHttpContractTests {
    @Test
    void pendingThenApprovalAndLostResponseRetryPreserveWireIdentityAndKey() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var phase = new AtomicInteger();
        var writes = new AtomicInteger();
        var keys = Collections.synchronizedList(new ArrayList<String>());
        var authHeaders = Collections.synchronizedList(new ArrayList<String>());
        var body = new AtomicReference<String>();
        var identityHeaders = new AtomicReference<List<String>>();
        String executionId = UUID.randomUUID().toString();
        String approvalId = UUID.randomUUID().toString();
        server.createContext("/api/v1/internal/executions/prepare", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            identityHeaders.set(List.of(exchange.getRequestHeaders().getFirst("X-User-Id"), exchange.getRequestHeaders().getFirst("X-Tenant-Id")));
            String status = phase.get() == 0 ? "PENDING" : "AUTHORIZED";
            String id = phase.get() == 0 ? "null" : "\"" + executionId + "\"";
            byte[] data = ("{\"status\":\"" + status + "\",\"approval_id\":\"" + approvalId
                    + "\",\"execution_id\":" + id + ",\"expires_at\":\"2099-01-01T00:00:00Z\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, data.length);
            exchange.getResponseBody().write(data);
            exchange.close();
        });
        var omsHeaders = new AtomicReference<List<String>>();
        server.createContext("/orders/1001/cancel", exchange -> {
            writes.incrementAndGet();
            keys.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            omsHeaders.set(List.of(exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("X-User-Id"),
                    exchange.getRequestHeaders().getFirst("X-Tenant-Id"),
                    exchange.getRequestHeaders().getFirst("X-Roles")));
            byte[] data = "{\"status\":\"CANCELLED\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            // 504 models an unknown remote outcome, not proof that OMS rolled back.
            exchange.sendResponseHeaders(phase.get() == 1 ? 504 : 200, data.length);
            exchange.getResponseBody().write(data);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            var mapper = JsonMapper.builder().build();
            var authorizer = new HttpExecutionAuthorizer(RestClient.builder(), new ExecutionServiceProperties(url, "execution-secret-longer-than-32-characters", "/api/v1/internal/executions/prepare"));
            var gateway = new HttpOrderGateway(RestClient.builder(), new OrderServiceProperties(url, "oms-token", "/orders/{orderId}/status", "/orders/{orderId}/cancel"));
            var service = new OrderService(gateway, authorizer, mapper);
            var identity = new BusinessIdentity("user", "tenant", Set.of("support.agent"));
            String cid = UUID.randomUUID().toString();
            assertEquals("PENDING", service.cancelOrder("1001", identity, cid).get("status").asText());
            assertEquals(0, writes.get());
            phase.set(1);
            assertThrows(RuntimeException.class, () -> service.cancelOrder("1001", identity, cid));
            assertEquals(1, writes.get()); // no blind automatic POST retry
            phase.set(2);
            assertEquals("CANCELLED", service.cancelOrder("1001", identity, cid).get("status").asText());
            assertEquals(List.of(executionId, executionId), keys);
            assertEquals(List.of("Bearer oms-token", "user", "tenant", "support.agent"), omsHeaders.get());
            assertEquals(List.of("user", "tenant"), identityHeaders.get());
            assertTrue(authHeaders.stream().allMatch(h -> h.equals("Bearer execution-secret-longer-than-32-characters")));
            var request = mapper.readTree(body.get());
            assertEquals(cid, request.get("conversation_id").asText());
            assertEquals("order.cancel", request.get("operation").asText());
            assertEquals("1001", request.get("arguments").get("orderId").asText());
            assertNull(request.get("execution_id"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void invalidAuthorizationResponseFailsClosedWithoutLeakingItsBody() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/internal/executions/prepare", exchange -> {
            byte[] data = "{\"status\":\"AUTHORIZED\",\"private\":\"secret-in-body\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, data.length);
            exchange.getResponseBody().write(data);
            exchange.close();
        });
        server.start();
        try {
            var client = new HttpExecutionAuthorizer(RestClient.builder(), new ExecutionServiceProperties(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "execution-secret-longer-than-32-characters", "/api/v1/internal/executions/prepare"));
            var error = assertThrows(IllegalStateException.class, () -> client.prepare(UUID.randomUUID().toString(), "order.cancel", Map.of("orderId", "1001"), new BusinessIdentity("u", "t", Set.of())));
            assertFalse(error.toString().contains("secret-in-body"));
            assertNull(error.getCause());
        } finally {
            server.stop(0);
        }
    }
}
