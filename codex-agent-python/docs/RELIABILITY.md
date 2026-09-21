# 单 Agent 可靠性与复用边界

[项目首页](../../README.md) · [接入指南](INTEGRATION.md) · [验收状态](PRODUCTION_READINESS.md)

这次改动解决 PR #20 的 CI、真实 SDK 事件契约、评测误判和单实例并发治理。它不代表已经完成真实 OMS、身份平台和生产部署验收。

## 可以复用什么

| 模块 | 职责 | 换业务 Agent 时 |
|---|---|---|
| `app/executions/service.py` | 操作注册、会话归属、参数指纹与持久化授权端口 | 复用，业务校验器另行实现 |
| `app/runtime/ports.py` | 应用层依赖的 Runtime Protocol，无 Codex SDK 类型 | 复用 |
| `app/runtime/admission.py` | 每个会话互斥、总并发上限、执行期限、停止接单与排空 | 复用 |
| `app/runtime/event_subscription.py` | 有界 SSE 订阅、慢消费者处理、断线与执行解耦 | 复用 |
| `app/events/codex_event_mapper.py` | 锁定 SDK 的通知到稳定 AgentEvent 的适配 | 复用，升级 SDK 时运行契约测试 |
| `evals/langsmith_target.py` | 真实 HTTP/SSE 黑盒 Target 与完整性校验 | 复用相同 API 契约 |
| `evals/gate.py` | 缺失、跳过、执行失败、评分失败均不放行 | 复用 |
| Skill、Tool、Policy、题库、业务质量 Judge | 业务规则和验收标准 | 必须重新设计和验证 |

应用服务依赖 `AgentRuntime` Protocol。Codex 仍实现 Agent Loop、Thread、Context、Compaction、Tool Dispatch；本项目没有另写一套 Agent 循环。

## 当前支持的部署拓扑

**一个 Agent Service 进程、一个事件循环、一个 Codex Runtime、一个专属持久化 CODEX_HOME。** Dockerfile 固定 `--workers 1`。部署副本必须为 1，发布必须先停止旧 Runtime，再挂载原持久卷启动新 Runtime，禁止滚动发布期间两个 Runtime 同时写同一 CODEX_HOME。

进程内互斥不是分布式锁。不能增加 Uvicorn worker 或直接扩成多个副本来获得正确的多 Runtime 调度。达到容量瓶颈后，需要独立设计路由、持久卷归属、跨进程互斥和接管协议。

| 配置 | 默认值 | 含义 |
|---|---|---|
| `MAX_ACTIVE_OPERATIONS` | 8 | 同时进入 Runtime 的操作上限，含创建、读取、压缩及普通/流式 Turn |
| `OPERATION_TIMEOUT_SECONDS` | 180 | 一个受控操作的执行期限 |
| `SHUTDOWN_TIMEOUT_SECONDS` | 30 | 关闭 Runtime 前等待执行任务结束的时间 |

这版采用立即拒绝的背压策略，不维护无界等待队列。同一会话冲突返回 HTTP 409；总容量已满返回 HTTP 503，并附 `Retry-After: 1`。所有权校验在占用 Runtime 前执行，外部只能使用自己的 conversation_id。数据库同步调用在线程池中执行，避免阻塞事件循环。

流式入口在返回 SSE 响应头之前完成所有权和并发准入，因而 404/409/503 是真实 HTTP 状态码。开始流式响应后的故障通过脱敏 `error` 事件返回。

## 断线不等于任务停止

HTTP 消费者不拥有执行任务。普通请求使用 shield；流式请求由服务持有后台生产任务。断线只取消订阅，后台仍消费 Runtime 事件，直到操作结束或执行期限到达。此期间同一会话仍不能发起新操作。

每个订阅最多缓存 128 个事件。慢消费者溢出后收到 `STREAM_CONSUMER_TOO_SLOW`，队列清空并脱离订阅，后台不会因向这个客户端发送数据而无限阻塞。当前没有 SSE 历史重放和断点续传；客户端不能把断线理解为业务失败并自动重发写请求。

## 执行状态不明时停止接单

正常结束或明确的终态失败可以释放占用。超时、SDK 通信失败等情况可能无法证明远端 Turn 停止，此时控制器关闭准入，`/api/v1/ready` 返回 503，后续操作被拒绝。异常响应只包含稳定错误码，不带 Provider 消息、内部 URL、密钥或工具参数。

**取消 Python 协程不等于撤销已发出的 OMS 操作。** 这版不会因为超时而自动重试写操作，也不会声称已经回滚。运维必须核对 Thread 与真实 OMS 的执行结果、停止旧 Runtime，再决定恢复服务。关闭 Runtime 也不能撤销已经到达 OMS 的请求。

退出时先关闭准入，等待活跃任务，关闭 Runtime，再清理本地任务和数据库资源。`/health` 仍是存活探针；`/ready` 结合准入状态与后台数据库探测（最多 60 秒陈旧期限）；不探测模型提供商或真实 OMS 的全链路可用性。平台终止宽限期应大于应用排空时限，并在真实部署环境验证。

## 手动压缩

`thread/compact/start` 的 RPC 返回值只表示已接受。适配器记录压缩前的 Turn ID，发起压缩后读取该 Thread 的新 Turn，直到发现包含 `contextCompaction` 项目的已完成 Turn 才释放会话占用。接口成功状态改为 `COMPACTION_COMPLETED`，不再将“已发起”作为“已完成”。超时则按执行状态不明处理。

该行为遵循 [Codex App Server 的压缩协议](https://learn.chatgpt.com/docs/app-server#trigger-thread-compaction)。目前有 SDK 类型与受控快照测试，仍需在真实模型环境运行一次压缩验收。

## 评测门禁

- Target 要求合法 SSE、正确 conversation_id、匹配事件类型及成功的 `turn.completed`。EOF、HTTP 错误、失败/中断终态、错误事件都不是通过。
- 单次流读取设有字节上限、读超时和消费时限检查；没有终止事件的答案不能参与成功评分。
- Fixture 不可用明确 SKIPPED，评分为 `None`，不算通过。空数据集、任一跳过、执行错误、确定性分数缺失或低于 1，CLI 返回非零退出码。
- LLM Judge 是可选的业务质量信号；当前发布门禁要求三个确定性评分全部为 1。不能仅凭这三个分数证明 OMS 已完成写操作或完成所有安全验收。
- `evals/run.py` 与 LangSmith 共用 Target 和确定性 Evaluator，避免两套解析器产生不同结论。
- Seed 同步只捕获 Dataset 不存在异常；认证/网络错误直接失败。已修改的 seed 更新原 example，保留其他人工 metadata；不覆盖非 seed 来源的例题。不支持并发运行两个 seed 导入任务。
- Eval 必须提供独立 `EVAL_API_SHARED_SECRET`，不回退到生产 `API_SHARED_SECRET`。真实实验需要专用测试环境及脱敏数据。

## Java CI 修复

Spring Boot 4 的服务端 Web MVC starter 并不代替 RestClient starter。本项目显式引入 `spring-boot-starter-restclient`，让 Boot 自动配置 `RestClient.Builder`，保留消息转换器和 HTTP 客户端定制能力，而不是在测试里伪造 Bean。

依据：[Spring Boot REST 客户端文档](https://docs.spring.io/spring-boot/reference/io/rest-client.html)。

## 仍然需要完成的上线工作

1. 审批与 Adapter 已贯穿 execution_id / Idempotency-Key；仍须真实 OMS 实现原子去重并完成验收，见 [执行幂等契约](EXECUTION_CONTRACT.md)。
2. 审批到实际工具执行的完整验收；Tool timeout/retry 策略及恶意 Tool Result fixture。
3. 企业身份验证、密钥管理、网络边界与日志脱敏验收。
4. CODEX_HOME 和 PostgreSQL 的部署、备份、崩溃恢复演练。
5. 真实 Codex → MCP → 测试 OMS → LangSmith Experiment；全部关键 Case 和 fixture 必须通过。
6. 部署层排空、长连接代理、容量压测、指标与告警。

本地单元/HTTP/契约测试使用测试替身验证边界和失败行为，绝不作为真实模型、真实 OMS 或生产恢复演练的替代证据。
