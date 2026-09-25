package com.example.hanresstest.security;

import com.example.hanresstest.config.McpSecurityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 保护 MCP HTTP Endpoint，只接受持有服务凭据的 Agent Runtime。
 *
 * <p>用户和租户 Header 只有在该服务认证通过后才会被下游信任。</p>
 */
@Component
public class McpServiceAuthenticationFilter extends OncePerRequestFilter {

    static final String AUTHENTICATED = McpServiceAuthenticationFilter.class.getName() + ".authenticated";

    private final McpSecurityProperties properties;

    public McpServiceAuthenticationFilter(McpSecurityProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/mcp");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        String expected = "Bearer " + properties.serviceToken();
        if (authorization == null || !constantTimeEquals(authorization, expected)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "MCP service authentication failed");
            return;
        }
        request.setAttribute(AUTHENTICATED, Boolean.TRUE);
        filterChain.doFilter(request, response);
    }

    private static boolean constantTimeEquals(String actual, String expected) {
        return MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8)
        );
    }
}
