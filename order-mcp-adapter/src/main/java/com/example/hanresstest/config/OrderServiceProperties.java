package com.example.hanresstest.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 真实订单系统的连接配置。
 *
 * <p>生产环境不提供默认业务地址或测试凭据；缺失配置时应用直接启动失败。</p>
 */
@ConfigurationProperties(prefix = "order-service")
public record OrderServiceProperties(
        String baseUrl,
        String serviceToken,
        String statusPath,
        String cancelPath
) {
    public OrderServiceProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("必须配置 order-service.base-url");
        }
        if (serviceToken == null || serviceToken.isBlank()) {
            throw new IllegalArgumentException("必须配置 order-service.service-token");
        }
        statusPath = requirePath(statusPath, "order-service.status-path");
        cancelPath = requirePath(cancelPath, "order-service.cancel-path");
    }

    private static String requirePath(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("必须配置 " + key);
        }
        return value;
    }
}
