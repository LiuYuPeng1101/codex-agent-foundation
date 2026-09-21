# 执行授权与 OMS 幂等契约 v1

本仓库实现审批记录、执行 ID、MCP 强制门禁及 OMS HTTP Header 传递。真实 OMS 不在此仓库中，不能仅凭本仓库测试宣称业务写入已经实现一次性效果。

## 内部授权接口

`POST /api/v1/internal/executions/prepare` 仅供可信 MCP Adapter 使用：

```http
Authorization: Bearer <EXECUTION_SERVICE_SECRET>
X-User-Id: <trusted-user>
X-Tenant-Id: <trusted-tenant>
Content-Type: application/json
```

```json
{
  "conversation_id": "40000000-0000-4000-8000-000000000001",
  "operation": "order.cancel",
  "arguments": {"orderId": "1001"}
}
```

新动作响应：

```json
{
  "status": "PENDING",
  "approval_id": "40000000-0000-4000-8000-000000000002",
  "execution_id": null,
  "expires_at": "2026-09-07T12:00:00Z"
}
```

批准后的相同请求返回 `AUTHORIZED` 及服务生成的 execution UUID；拒绝或过期返回 `REJECTED` / `EXPIRED` 且 ID 为 null。身份不属于会话返回 404；未认证返回 401；未知操作、额外字段、无效参数返回 422。示例 ID 和时间只用于说明格式。

操作注册表当前只接受 `order.cancel`，参数必须恰好为一个非空字符串 `orderId`，最长 128 字符，无首尾空白和控制字符。身份、execution_id、approval_id 都不是模型可指定的参数。`ExecutionService` 接收业务校验器和仓储端口，新增业务时注册自己的操作与参数规则。

幂等范围是 **同一会话、用户、租户、操作和验证后参数**。JSON 键排序后做版本化指纹，Unicode 保持原值，不把两个不同标识自动合并。数据库唯一索引覆盖已消费、拒绝与过期的新授权；行锁将批准签发序列化。

同一会话的完全相同动作被视为一次业务意图。退款、发券等允许同参重复发生的业务，必须将由可信业务系统分配的 intent ID 加入操作契约，并验证其归属，不能让模型随意生成 intent ID。跨会话动作不由本契约自动去重，OMS 仍须执行订单状态与业务唯一性检查。

## OMS 必须落实的行为

Adapter 对取消请求发送：

```http
POST <ORDER_CANCEL_PATH with orderId>
Authorization: Bearer <ORDER_SERVICE_TOKEN>
Idempotency-Key: <authorized-execution-uuid>
X-User-Id: <trusted-user>
X-Tenant-Id: <trusted-tenant>
X-Roles: <trusted-current-roles>
```

OMS 必须将幂等记录和订单更新放进同一数据库事务，或使用能证明同等原子性的既有业务设施。至少包含以下规则：

| 情形 | OMS 要求 |
|---|---|
| 首次有效 key | 验证调用方、租户、资源权限、业务状态；保存规范请求指纹并执行更新 |
| 相同 key、相同请求并发 | 只允许一个业务提交；其余等待同一结果或返回明确处理中 |
| 相同 key、已提交 | 返回原结果，不再次取消或发布业务事件 |
| 相同 key、不同操作/资源/用户/租户/参数 | 拒绝冲突，例如 HTTP 409；不得执行新动作 |
| 已提交但响应丢失 | 客户端使用原 key 查询或重试时仍能得到原结果 |
| 事务回滚 | 不留下看似成功的幂等记录；是否可重试由 OMS 明确定义 |
| 下游事件/消息 | 与业务提交通过事务 outbox 等机制衔接，消费者也按业务事件 ID 去重 |

指纹至少绑定 HTTP 操作、目标资源、规范业务参数和租户/用户；幂等键唯一范围不能让变更身份的同一个 key 悄悄变成新操作。重放前仍验证访问权，防止通过已知 key 读取别人的结果。唯一记录不得在请求还可能重放时提前清理；授权有效期、最长在途请求、故障重试窗口与 OMS 保留期须联合约定。人工对账前不得删除框架中的授权记录来“解锁”。

Adapter 不自动重试 POST、不跟随重定向；当前 HTTP 连接期限为 5 秒、读取期限为 15 秒。HTTP 504、断线或超时不能证明 OMS 没有提交。授权服务失败、空响应、未知状态、缺少 ID 或已过期都阻止 OMS 调用。授权过期后停止重试并人工对账，不生成新 key。

## 升级与配置

1. 停止旧 Runtime 并排空，确认没有旧 Adapter 写请求在途；避免新工具策略与旧版无门禁 Adapter 混用。
2. 先应用 `005_execution_authorization.sql`。旧审批不回填执行 ID，不可以用于新门禁；需要重新申请结构化审批。升级后 Python 启动检查会读取新增列，缺迁移直接失败。
3. 为 Python 设置独立 `EXECUTION_SERVICE_SECRET`，不能与 `API_SHARED_SECRET` 或 `ORDER_MCP_SERVICE_TOKEN` 相同。内部接口不得通过面向终端用户的入口暴露。
4. Java 设置同一内部密钥与 `EXECUTION_SERVICE_BASE_URL`。可配置 `EXECUTION_SERVICE_PREPARE_PATH`，默认 `/api/v1/internal/executions/prepare`；修改 Python `API_PREFIX` 时必须同步。
5. 部署新 Python 与 Java，再恢复业务流量。回滚工具策略前必须停止写流量并对账，不能在线换回绕过门禁的旧 Adapter。

服务凭据与控制面配置必须对模型的命令执行环境、工作目录和用户文件不可见，并通过网络边界限制 OMS 只接受业务 Adapter。当前通过 launcher 清理宿主环境并限制订单 Agent 的本地工具；仍须在部署验收中验证实际文件/网络边界，本次服务认证也不防御已被攻陷的 Adapter。

## 验证范围

- Python 单元/HTTP：独立密钥、会话归属、未知操作、参数伪造、稳定指纹、SDK 审批描述不能创建业务授权。
- PostgreSQL 集成：并发创建只有一个审批；并发签发共享一个 ID；重新创建仓储后 ID 不变；拒绝、过期不换 key；旧 grant API 不能消费新授权。
- Java 应用边界：待审批、拒绝、过期、授权服务失败均不访问 OMS。
- Java 本地 HTTP 合约：真实 JSON 编解码、可信 Header、同 key 重试、OMS 504、不自动重试、非法授权响应脱敏失败。这些服务是测试替身。

完整 Java MCP HTTP 测试已经覆盖会话 Header 和可信请求上下文，但其授权服务与 OMS 仍为替身。上线还必须在真实模型与真实 OMS 测试环境中验收整条链路，以及并发提交、重放、崩溃恢复和事件去重。必须用 OMS 的事务/审计结果作为验收依据，不能以模型声称“已取消”作为证据。
