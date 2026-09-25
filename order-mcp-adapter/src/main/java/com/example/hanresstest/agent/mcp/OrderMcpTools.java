package com.example.hanresstest.agent.mcp;

import com.example.hanresstest.security.BusinessIdentity;
import com.example.hanresstest.security.TrustedMcpRequestContext;
import com.example.hanresstest.service.OrderService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * 订单领域 MCP Tool 适配器。
 *
 * <p>模型只提供业务意图参数（orderId）。userId / tenantId / roles 从已经通过服务认证的
 * MCP HTTP 请求上下文读取，模型无法自行伪造这些身份字段。</p>
 */
@Component
public class OrderMcpTools {

    private final OrderService orderService;
    private final TrustedMcpRequestContext requestContext;

    public OrderMcpTools(OrderService orderService, TrustedMcpRequestContext requestContext) {
        this.orderService = orderService;
        this.requestContext = requestContext;
    }

    @Tool(
            name = "get_order_status",
            description = "根据订单ID查询真实订单系统中的当前状态、履约信息和预计送达信息"
    )
    public JsonNode getOrderStatus(
            @ToolParam(description = "业务订单ID") String orderId
    ) {
        BusinessIdentity identity = requestContext.currentIdentity();
        return orderService.getOrderStatus(orderId, identity);
    }

    @Tool(
            name = "cancel_order",
            description = "向真实订单系统发起取消订单操作。该写操作必须经过 Agent Approval 和业务系统授权。"
    )
    public JsonNode cancelOrder(
            @ToolParam(description = "业务订单ID") String orderId
    ) {
        BusinessIdentity identity = requestContext.currentIdentity();
        return orderService.cancelOrder(orderId, identity, requestContext.currentConversationId());
    }
}
