package com.example.hanresstest.gateway;

import com.example.hanresstest.config.OrderServiceProperties;
import com.example.hanresstest.security.BusinessIdentity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * 通过企业内部 HTTP API 访问真实订单系统。
 *
 * <p>服务凭据用于系统间认证；X-User-Id / X-Tenant-Id / X-Roles 表达当前业务主体。
 * 最终订单系统必须基于这些可信身份独立执行 RBAC / ABAC / Tenant / 资源归属和状态校验。</p>
 */
@Component
public class HttpOrderGateway implements OrderGateway {

    private final RestClient restClient;
    private final OrderServiceProperties properties;

    public HttpOrderGateway(RestClient.Builder builder, OrderServiceProperties properties) {
        this.properties = properties;
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5))
                        .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(java.time.Duration.ofSeconds(15));
        this.restClient = builder.clone().requestFactory(factory)
                .baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.serviceToken())
                .build();
    }

    @Override
    public JsonNode getOrderStatus(String orderId, BusinessIdentity identity) {
        return safeResponse(() -> restClient.get()
                .uri(properties.statusPath(), orderId)
                .headers(headers -> applyIdentity(headers, identity))
                .retrieve()
                .body(JsonNode.class));
    }

    @Override
    public JsonNode cancelOrder(String orderId, BusinessIdentity identity, java.util.UUID executionId) {
        java.util.Objects.requireNonNull(executionId, "执行 ID 不能为空");
        return safeResponse(() -> restClient.post()
                .uri(properties.cancelPath(), orderId)
                .header("Idempotency-Key", executionId.toString())
                .headers(headers -> applyIdentity(headers, identity))
                .retrieve()
                .body(JsonNode.class));
    }

    private static JsonNode safeResponse(java.util.function.Supplier<JsonNode> call) {
        try {
            JsonNode result = call.get();
            if (result == null) throw new IllegalStateException("ORDER_EMPTY_RESPONSE");
            return result;
        } catch (org.springframework.web.client.RestClientException exception) {
            // Downstream error bodies/URLs must never become model-visible tool errors.
            throw new IllegalStateException("ORDER_SERVICE_UNAVAILABLE");
        }
    }

    private static void applyIdentity(HttpHeaders headers, BusinessIdentity identity) {
        headers.set("X-User-Id", identity.userId());
        headers.set("X-Tenant-Id", identity.tenantId());
        headers.set("X-Roles", String.join(",", identity.roles()));
    }
}
