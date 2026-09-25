package com.example.hanresstest.config;

import com.example.hanresstest.agent.mcp.OrderMcpTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 注册对 Agent 暴露的 MCP Tool Adapter。 */
@Configuration
public class McpToolConfig {

    @Bean
    public ToolCallbackProvider orderTools(OrderMcpTools orderMcpTools) {
        return MethodToolCallbackProvider
                .builder()
                .toolObjects(orderMcpTools)
                .build();
    }
}
