package com.example.hanresstest.service;

import com.example.hanresstest.execution.ExecutionAuthorizer;
import com.example.hanresstest.execution.ExecutionDecision;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;

import com.example.hanresstest.gateway.OrderGateway;
import com.example.hanresstest.security.BusinessIdentity;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 订单应用服务。
 *
 * <p>该类不感知 MCP、LLM 或 Codex，只组织真实订单业务用例，并把可信身份继续传给订单系统。</p>
 */
@Service
public class OrderService {

    private final OrderGateway orderGateway;

    private final ExecutionAuthorizer authorizer;
    private final ObjectMapper mapper;

    public OrderService(OrderGateway orderGateway, ExecutionAuthorizer authorizer, ObjectMapper mapper) {
        this.orderGateway = orderGateway;
        this.authorizer = authorizer;
        this.mapper = mapper;
    }

    public JsonNode getOrderStatus(String orderId, BusinessIdentity identity) {
        return orderGateway.getOrderStatus(orderId, identity);
    }

    public JsonNode cancelOrder(String orderId, BusinessIdentity identity, String conversationId) {
        if (orderId == null || orderId.isBlank() || orderId.length() > 128
                || !orderId.equals(orderId.strip()) || orderId.chars().anyMatch(c -> c < 32)) {
            throw new IllegalArgumentException("无效订单 ID");
        }
        var decision = authorizer.prepare(conversationId, "order.cancel", Map.of("orderId", orderId), identity);
        if (decision.status() != ExecutionDecision.Status.AUTHORIZED) {
            // A pending/rejected/expired request is a business outcome, never an OMS success.
            return mapper.valueToTree(Map.of("status", decision.status().name(),
                    "approval_id", decision.approvalId().toString(),
                    "expires_at", decision.expiresAt().toString()));
        }
        return orderGateway.cancelOrder(orderId, identity, decision.requireExecutionId());
    }
}
