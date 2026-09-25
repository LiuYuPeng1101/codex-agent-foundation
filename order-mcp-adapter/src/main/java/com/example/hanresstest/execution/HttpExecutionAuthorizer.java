package com.example.hanresstest.execution;

import com.example.hanresstest.config.ExecutionServiceProperties;
import com.example.hanresstest.security.BusinessIdentity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@Component
public class HttpExecutionAuthorizer implements ExecutionAuthorizer {
    private final RestClient client;
    private final String preparePath;

    public HttpExecutionAuthorizer(RestClient.Builder builder, ExecutionServiceProperties properties) {
        this.preparePath = properties.preparePath();
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofSeconds(15));
        client = builder.clone().baseUrl(properties.baseUrl()).requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.serviceToken()).build();
    }

    @Override
    public ExecutionDecision prepare(String conversationId, String operation,
                                     Map<String, Object> arguments, BusinessIdentity identity) {
        UUID.fromString(conversationId);
        try {
            var result = client.post().uri(preparePath)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-User-Id", identity.userId())
                    .header("X-Tenant-Id", identity.tenantId())
                    .body(Map.of("conversation_id", conversationId,
                            "operation", operation, "arguments", arguments))
                    .retrieve().body(ExecutionDecision.class);
            if (result == null) {
                throw new IllegalStateException("EMPTY_AUTHORIZATION_RESPONSE");
            }
            return result;
        } catch (RuntimeException exception) {
            // Never continue to OMS after a timeout, bad response or authorization failure.
            // Do not expose internal URLs/response bodies through the model-facing tool error.
            throw new IllegalStateException("执行授权服务不可用，订单操作未发送");
        }
    }
}
