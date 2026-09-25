package com.example.hanresstest;

import com.example.hanresstest.config.McpSecurityProperties;
import com.example.hanresstest.security.McpServiceAuthenticationFilter;
import com.example.hanresstest.security.TrustedMcpRequestContext;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class TrustedContextTests {
    @Test
    void forgedHeadersAreNotTrustedUntilTheFilterAuthenticates() throws Exception {
        var request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("X-User-Id", "user");
        request.addHeader("X-Tenant-Id", "tenant");
        String cid = UUID.randomUUID().toString();
        request.addHeader("X-Conversation-Id", cid);
        var context = new TrustedMcpRequestContext();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            assertThrows(IllegalStateException.class, context::currentIdentity);
            assertThrows(IllegalStateException.class, context::currentConversationId);
            var filter = new McpServiceAuthenticationFilter(new McpSecurityProperties("mcp-token-with-at-least-32-characters"));
            var denied = new MockHttpServletResponse();
            filter.doFilter(request, denied, (req, res) -> fail("Unauthenticated request reached tools"));
            assertEquals(401, denied.getStatus());
            request.addHeader("Authorization", "Bearer mcp-token-with-at-least-32-characters");
            filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                assertEquals("user", context.currentIdentity().userId());
                assertEquals(cid, context.currentConversationId());
            });
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }
}
