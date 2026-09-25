package com.example.hanresstest.execution;

import com.example.hanresstest.security.BusinessIdentity;
import java.util.Map;

/** 可复用的控制面端口；操作名由应用服务声明，身份和会话来自可信上下文。 */
public interface ExecutionAuthorizer {
    ExecutionDecision prepare(String conversationId, String operation,
                              Map<String, Object> arguments, BusinessIdentity identity);
}
