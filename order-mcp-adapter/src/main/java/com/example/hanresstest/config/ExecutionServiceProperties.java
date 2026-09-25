package com.example.hanresstest.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "execution-service")
public record ExecutionServiceProperties(String baseUrl, String serviceToken, String preparePath) {
    public ExecutionServiceProperties {
        if (preparePath == null || !preparePath.startsWith("/") || preparePath.startsWith("//")) {
            throw new IllegalArgumentException("必须配置 execution-service.prepare-path 为相对服务路径");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("必须配置 execution-service.base-url");
        }
        if (serviceToken == null || serviceToken.length() < 32) {
            throw new IllegalArgumentException("execution-service.service-token 至少需要 32 个字符");
        }
    }
}
