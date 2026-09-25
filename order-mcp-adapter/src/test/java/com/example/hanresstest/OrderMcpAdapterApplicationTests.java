package com.example.hanresstest;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "execution-service.base-url=http://127.0.0.1:65535",
        "execution-service.service-token=test-execution-service-secret-1234567890",
        "mcp.security.service-token=test-mcp-service-token-1234567890-abcd",
        "order-service.base-url=http://127.0.0.1:65535",
        "order-service.service-token=test-order-service-token",
        "order-service.status-path=/api/orders/{orderId}/status",
        "order-service.cancel-path=/api/orders/{orderId}/cancel"
})
class OrderMcpAdapterApplicationTests {

    @Test
    void contextLoads() {
    }
}
