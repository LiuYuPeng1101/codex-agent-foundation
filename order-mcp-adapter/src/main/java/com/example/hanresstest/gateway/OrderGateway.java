package com.example.hanresstest.gateway;

import com.example.hanresstest.security.BusinessIdentity;
import tools.jackson.databind.JsonNode;

/**
 * 订单系统访问端口。
 *
 * <p>业务身份作为控制面上下文显式传递，不允许从模型 Tool 参数中构造。</p>
 */
public interface OrderGateway {

    JsonNode getOrderStatus(String orderId, BusinessIdentity identity);

    JsonNode cancelOrder(String orderId, BusinessIdentity identity, java.util.UUID executionId);
}
