package com.example.hanresstest.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent Runtime 调用 MCP Adapter 的服务认证配置。
 */
@ConfigurationProperties(prefix = "mcp.security")
public record McpSecurityProperties(String serviceToken) {
    public McpSecurityProperties {
        if (serviceToken == null || serviceToken.length() < 32) {
            throw new IllegalArgumentException("必须配置至少 32 字符的 mcp.security.service-token");
        }
    }
}
