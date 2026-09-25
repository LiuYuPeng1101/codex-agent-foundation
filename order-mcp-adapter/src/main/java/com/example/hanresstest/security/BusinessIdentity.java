package com.example.hanresstest.security;

import java.util.Set;

/**
 * 由可信 Agent Runtime 控制面传入的业务身份。
 *
 * <p>该身份不来自模型 Tool 参数。MCP Adapter 只在服务认证通过后从 HTTP Header 构造，
 * 然后继续传给真实订单系统做最终 RBAC / ABAC / Tenant / 资源归属校验。</p>
 */
public record BusinessIdentity(
        String userId,
        String tenantId,
        Set<String> roles
) {
    public BusinessIdentity {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("缺少可信 userId");
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("缺少可信 tenantId");
        }
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }
}
