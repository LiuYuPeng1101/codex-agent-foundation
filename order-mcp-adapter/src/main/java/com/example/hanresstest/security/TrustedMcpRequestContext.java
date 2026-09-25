package com.example.hanresstest.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 从当前已通过 MCP 服务认证的 HTTP 请求中读取可信业务身份。
 *
 * <p>身份 Header 由 Agent Runtime 控制面注入，而不是由模型作为 Tool 参数生成。</p>
 */
@Component
public class TrustedMcpRequestContext {

    public BusinessIdentity currentIdentity() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            throw new IllegalStateException("当前 MCP Tool 调用缺少 HTTP 请求上下文");
        }

        HttpServletRequest request = attributes.getRequest();
        requireAuthenticated(request);
        String userId = requireHeader(request, "X-User-Id");
        String tenantId = requireHeader(request, "X-Tenant-Id");
        String rolesHeader = request.getHeader("X-Roles");

        Set<String> roles = rolesHeader == null || rolesHeader.isBlank()
                ? Set.of()
                : Arrays.stream(rolesHeader.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());

        return new BusinessIdentity(userId, tenantId, roles);
    }

    public String currentConversationId() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            throw new IllegalStateException("当前 MCP Tool 调用缺少 HTTP 请求上下文");
        }
        requireAuthenticated(attributes.getRequest());
        String value = requireHeader(attributes.getRequest(), "X-Conversation-Id");
        return java.util.UUID.fromString(value).toString();
    }

    private static void requireAuthenticated(HttpServletRequest request) {
        if (!Boolean.TRUE.equals(request.getAttribute(McpServiceAuthenticationFilter.AUTHENTICATED))) {
            throw new IllegalStateException("MCP 请求尚未通过服务认证");
        }
    }

    private static String requireHeader(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("可信 MCP 请求缺少 Header: " + name);
        }
        return value.trim();
    }
}
