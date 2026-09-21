# Order MCP Adapter

[项目总览](../README.md) · [完整启动步骤](../codex-agent-python/docs/GETTING_STARTED.md) · [接入现有系统](../codex-agent-python/docs/INTEGRATION.md) · [验收清单](../codex-agent-python/docs/PRODUCTION_READINESS.md)

参考工具链：JDK 21、Spring Boot 4.1.1、Spring AI 2.0.1（以 pom.xml 为准）。可独立部署，通过 Gateway 调用原业务系统，不要求旧项目迁移到同一 Spring Boot 版本。

这个模块是企业订单系统的 **Spring Boot MCP Adapter**。它不是 Agent Runtime，也不拥有订单事实。

生产职责：

```text
Codex Runtime
  │
  │ Authorization: MCP service credential
  │ X-User-Id / X-Tenant-Id / X-Roles
  ▼
McpServiceAuthenticationFilter
  ↓
TrustedMcpRequestContext
  ↓
BusinessIdentity
  ↓
OrderMcpTools
  ↓
OrderService
  ↓
OrderGateway
  ↓
HttpOrderGateway
  ↓
真实 OMS / System of Record
```

## 为什么 Java 只保留 MCP Adapter？

Codex Runtime 已统一放在 Python。如果 Java 再启动一份 Codex，会形成两套 Thread、Approval、Event 和 Context 生命周期，因此生产主路径已经删除 Java Codex Runtime。

Java 应负责它最擅长的事情：**把已有企业业务能力以受治理的 MCP Tool 暴露出去。**

## 为什么 Tool 只有 orderId，没有 userId / tenantId？

当前 Tool：

```text
get_order_status(orderId)
cancel_order(orderId)
```

身份不能设计成模型参数：

```text
cancel_order(orderId, userId, tenantId, role)  # 错误
```

因为模型输出不是身份凭证。

可信身份来自上游 Runtime 的控制面 Header。MCP Adapter 先通过 `McpServiceAuthenticationFilter` 验证 Runtime 的服务凭据，只有验证成功后才由 `TrustedMcpRequestContext` 读取：

```text
X-User-Id
X-Tenant-Id
X-Roles
X-Conversation-Id  # 写操作必需
```

并构造 `BusinessIdentity`。

## 为什么身份还要继续传给 OMS？

Adapter 在取消操作前向 ExecutionService 取得人工批准的固定执行 ID。最终业务操作仍必须由真实订单系统判断：

```text
当前用户是否有权限？
订单是否属于当前 tenant？
用户是否能访问这个订单？
当前订单状态是否允许取消？
金额或风控规则是否允许？
```

因此 `HttpOrderGateway` 同时传递：

```text
ORDER_SERVICE_TOKEN       # MCP Adapter → OMS 服务认证
X-User-Id                 # 当前业务用户
X-Tenant-Id               # 当前企业租户
X-Roles                   # 当前实时角色
```

真正的 RBAC / ABAC / Tenant / Resource Ownership 必须由 OMS 再执行一次。

## 为什么要拆 OrderMcpTools / OrderService / OrderGateway？

```text
OrderMcpTools
= Agent 协议适配，只定义模型可见业务能力

OrderService
= 应用用例，不依赖 Codex / MCP

OrderGateway
= 访问订单系统的端口

HttpOrderGateway
= 当前 HTTP 基础设施实现
```

如果企业订单系统以后从 REST 换成 Dubbo / gRPC / SDK，只替换 Gateway 实现，不改变 Agent Tool 语义。

## 生产依赖

必须配置：

```text
MCP_SERVICE_TOKEN
ORDER_SERVICE_BASE_URL
ORDER_SERVICE_TOKEN
EXECUTION_SERVICE_BASE_URL
EXECUTION_SERVICE_SECRET
```

可覆盖：

```text
ORDER_STATUS_PATH
ORDER_CANCEL_PATH
EXECUTION_SERVICE_PREPARE_PATH
```

服务不会使用固定订单、内存状态或测试结果兜底。依赖不可用时明确失败。

## 启动示意

```bash
export MCP_SERVICE_TOKEN='replace-with-strong-runtime-service-secret'
export ORDER_SERVICE_BASE_URL='https://oms.internal.example'
export ORDER_SERVICE_TOKEN='replace-with-order-service-credential'

export EXECUTION_SERVICE_BASE_URL='http://agent-service:8000'
export EXECUTION_SERVICE_SECRET='replace-with-distinct-internal-execution-secret'

mvn spring-boot:run
```

MCP Endpoint：

```text
/mcp
```

生产部署还应在网络层叠加 mTLS / Service Mesh / NetworkPolicy，并由 Secret Manager 管理和轮换服务凭据。

写操作执行授权、升级次序及 OMS 原子去重要求见 [执行契约](../codex-agent-python/docs/EXECUTION_CONTRACT.md)。当前 Adapter 只有取得有效授权后才调用 OMS，并携带 `Idempotency-Key`；重试沿用同一 ID。

## 新增：真实 MCP 传输验证

`McpTransportTests` 启动完整 Spring Boot 服务，通过 HTTP 执行 initialize、notifications/initialized 和 tools/call，检查读取身份、未批准禁止写入、批准后携带固定 execution ID 和跨会话身份传递。授权服务和 OMS 使用测试 HTTP 服务，测试不消耗模型 API，不替代真实 OMS 原子去重验收。

```bash
mvn -B test
```

OMS 的 HTTP 错误统一转换为 `ORDER_SERVICE_UNAVAILABLE`，不向工具调用方传递原始错误正文/内部 URL；空成功响应也视为失败。授权服务继续使用独立错误边界。
