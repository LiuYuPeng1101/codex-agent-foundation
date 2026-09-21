# Agent Service：运行与接口参考

[项目总览](../README.md) · [完整上手步骤](docs/GETTING_STARTED.md) · [接入现有系统](docs/INTEGRATION.md) · [Codex / AgentScope 对比](docs/HARNESS_COMPARISON.md) · [上线验收](docs/PRODUCTION_READINESS.md)

本模块提供基于 Codex Harness 的单业务 Agent 服务。当前装配为订单 Agent，通过 Java MCP Adapter 查询或申请取消订单。首次使用按上手指南启动；本页用于查配置、接口和排障。

## 代码导航

| 位置 | 职责 |
|---|---|
| `.agents/skills/order-analysis/SKILL.md` | 订单 SOP，由宿主读取并注入新 Thread |
| `app/core/lifespan.py` | 依赖装配、当前业务定义与操作注册、启停 |
| `app/agents/definition.py` | Agent/MCP/Tool Policy 定义 |
| `app/runtime/ports.py` | 应用层依赖的 Runtime Protocol |
| `app/runtime/codex_runtime.py` | SDK 薄适配，Thread/Turn/压缩、可信 MCP Header |
| `app/runtime/launcher.py`、`policy.py` | 实际 exec 环境白名单与本地能力限制 |
| `app/runtime/admission.py`、`event_subscription.py` | 并发、执行期限、排空、有界 SSE 订阅 |
| `app/services/agent_service.py`、`app/conversations/` | 会话归属与 Runtime 调用 |
| `app/executions/`、`app/approval/` | 结构化操作授权、审批、分页与持久化 |
| `app/security/`、`app/api/v1/`、`app/schemas/` | 服务身份、HTTP 接口与数据契约 |
| `app/events/`、`app/observability/` | 对外事件与 OTel Trace |
| `app/core/database.py`、`readiness.py` | PostgreSQL 期限与后台依赖探测 |
| `migrations/`、`tests/`、`evals/` | 数据迁移、代码测试和真实 Agent 评测 |

## 必需配置

设置项定义在 [config.py](app/core/config.py)，`.env` 相对于进程工作目录读取；环境变量优先。应用 `.env` 不等于其他进程自动继承配置，上手指南使用显式加载方式启动多个服务。

| 配置 | 作用 / 示例 |
|---|---|
| `CODEX_HOME` | 可写、专属、持久化的 Codex 状态目录 |
| `AGENT_WORKSPACE` | 含订单 Skill 的内容目录；本地 Python 项目目录，镜像 `/agent` |
| `DATABASE_URL` | `postgresql+psycopg://...`，不是 MySQL/SQLite |
| `API_SHARED_SECRET` | 可信业务后端调用本服务的密钥，至少 32 字符 |
| `ORDER_MCP_URL` | Java MCP Endpoint，例如 `http://127.0.0.1:8080/mcp` |
| `ORDER_MCP_SERVICE_TOKEN` | 对应 Java `MCP_SERVICE_TOKEN`，至少 32 字符 |
| `EXECUTION_SERVICE_SECRET` | 对应 Java 同名配置，至少 32 字符；必须不同于上面两个密钥 |

模型和认证由配套 Codex CLI 及专属 CODEX_HOME 配置管理；没有 `MODEL_NAME` 这样的应用配置项。参见[上手指南](docs/GETTING_STARTED.md)。`AGENT_ID` 当前是服务标识，不会切换订单工具或 SOP。

## 运行安全与联调配置

| 配置 | 默认值 | 含义 |
|---|---|---|
| `API_PREFIX` | `/api/v1` | 修改后需同步 Java 内部授权路径和调用方 |
| `AGENT_ID` | `order-agent` | Agent 标识 |
| `MAX_ACTIVE_OPERATIONS` | 8 | Runtime 操作总并发，含创建/读取/压缩/Turn |
| `OPERATION_TIMEOUT_SECONDS` | 180 秒 | 受控操作期限 |
| `SHUTDOWN_TIMEOUT_SECONDS` | 30 秒 | 应用排空等待期限 |
| `EXECUTION_GRANT_TTL_SECONDS` | 86400 秒 | 新执行授权有效期，过期不换新 key |
| `DATABASE_CONNECT_SECONDS` | 3 秒 | PostgreSQL 连接超时 |
| `DATABASE_POOL_SECONDS` | 2 秒 | 连接池等待超时 |
| `DATABASE_STATEMENT_MS` | 5000 毫秒 | SQL 语句期限 |
| `DATABASE_LOCK_MS` | 1000 毫秒 | 锁等待期限，不得大于 SQL 期限 |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | 未配置 | 可选 OTLP HTTP Trace Endpoint |

每个仓储池最多 5 个连接，不额外溢出扩容；会话与审批各有连接池，需要合并计算数据库容量。连接启用 TCP 保活/user timeout。SQL 异常映射为安全 503，不表示远端订单操作已回滚。

后台数据库探测每轮结束后等 5 秒；失败或最后成功超过 60 秒使 ready 失败。Runtime 因执行结果不明关闭准入时，即使数据库恢复，也不能自动判为可接单。处理语义见[可靠性文档](docs/RELIABILITY.md)。

### Codex 进程边界

SDK 0.147 会合并宿主环境，单靠 `config.env` 不足以隔离业务凭据。launcher 在实际 exec 前清理环境，仅保留基础运行变量、CODEX_HOME、OPENAI_API_KEY 和证书路径；自定义 provider 密钥与代理变量不会自动透传。

订单 Runtime 固定 READ_ONLY，限制 Shell/JS/浏览器/插件/子 Agent 等旁路。某些模型仍可能暴露 apply_patch，文件写入还须由沙箱阻止。MCP 服务密钥仍由可信 Runtime 控制面使用；环境清理不等于密钥从整个进程/磁盘消失，也不等于提供任意代码执行的 OS 隔离。

Docker 将内容放在 `/agent`，应用代码由 root 持有，运行用户只拥有 `/var/lib/codex`。专属状态目录不应包含个人 Codex 配置、上传文件或未经审查的跨会话 memory；相关隔离尚需部署验收。

### 支持的部署拓扑

`--workers 1`，一个 Runtime，一个专属 CODEX_HOME，部署副本为 1。已有 Dockerfile 提供服务镜像，但不自动迁移数据库、不提供 OMS、也没有完成生产部署声明与灾备配置。先停止旧 Runtime 再启动新 Runtime，禁止滚动发布期间共写状态盘。

## HTTP API

默认前缀 `/api/v1`。`{id}` 是业务 conversation ID，`{approval_id}` 是审批 ID。

| 方法与路径 | 身份要求 | 返回 / 用途 |
|---|---|---|
| `GET /health` | 无 | 进程存活 |
| `GET /ready` | 无 | 准入与后台数据库状态；不探测模型/OMS |
| `POST /agent/conversations` | 服务认证 + user/tenant | `conversation_id`；无请求正文 |
| `POST /agent/conversations/{id}/turns` | 服务认证 + 会话所有者 | 正文 `message`；返回 `answer` |
| `POST /agent/conversations/{id}/turns/stream` | 同上 | POST SSE 流 |
| `GET /agent/conversations/{id}` | 所有者 + `agent.operator` | 原始诊断快照，不是普通聊天历史接口 |
| `POST /agent/conversations/{id}/compact` | 所有者 + `agent.operator` | 等待压缩完成，返回 `COMPACTION_COMPLETED` |
| `GET /approvals` | 同租户 + `agent.approver` | `items` 和 `next_cursor` |
| `GET /approvals/{approval_id}` | 同上 | 跨租户 404 |
| `POST /approvals/{approval_id}/approve` | 同上 | 更新审批；无正文；不自动继续 Turn |
| `POST /approvals/{approval_id}/reject` | 同上 | 更新审批；无正文 |
| `POST /internal/executions/prepare` | 独立内部密钥 + 会话归属 | 仅供 Adapter；见[执行契约](docs/EXECUTION_CONTRACT.md) |

发送消息的 JSON：`{"message":"查询订单 1001"}`，长度 1–32000。FastAPI `/docs` 和 `/openapi.json` 可用于开发环境查看 Schema；生产入口应限制诊断和文档访问。

审批列表支持 `limit=1..200`（默认 50）、`cursor`、`conversation_id`、`status=PENDING|APPROVED|REJECTED|CONSUMED`。下一页保持原筛选条件；`next_cursor=null` 为结束。PENDING 排除已过期的新执行授权；EXPIRED 是授权判定结果，不是审批列表支持的筛选状态。分页不是跨页数据库快照。

## SSE 契约

```text
event: message.delta
data: {"type":"message.delta","conversation_id":"<uuid>","data":{"delta":"查询结果"},"created_at":"<ISO-8601>"}

```

事件之间为空行。可能出现 `turn.started`、`message.delta`、`tool.started/completed`、`item.started/completed`、`turn.completed` 和 `error`。工具事件含名称/状态，不直接透传原始参数或结果；模型生成的回答仍需通过泄露用例与业务脱敏验收。

- **成功条件**：收到 `turn.completed`，其 `data.status=completed`，没有错误。
- `turn.completed` 也可能携带失败状态；EOF、200、部分回答不能算成功。
- SSE 响应头发送前的 404/409/503 是真实 HTTP 状态；发送后的失败通过流内错误处理。
- 慢消费者最多缓存 128 个事件；溢出后脱离订阅。断线不取消后台执行。
- 当前无历史重放、断点续传和专用 SSE 心跳；代理的缓冲/空闲期限必须实测。

## 故障处理

| 现象 | 调用方处理 |
|---|---|
| 401 / 400 | 检查服务密钥及身份 Header，不请求模型“修复权限” |
| 403 | 检查审批/运维角色 |
| 404 | 检查 ID 与当前 user/tenant；不返回其他租户的信息 |
| 409 | 同会话操作冲突，或审批不能按当前状态改变；读取具体错误 |
| 503 + `Retry-After` | 区分容量与数据库/Runtime 故障，不对所有请求统一重试 |
| `RUNTIME_OUTCOME_UNKNOWN` | 停止接单，核对 Thread 与 OMS 结果，再按流程恢复 |
| `STREAM_CONSUMER_TOO_SLOW` / 连接断开 | 不推断业务失败，不自动重复写动作 |
| `CONSUMED` | 只说明 ID 已签发，查询 OMS 确认业务结果 |

## 开发验证

```bash
python -m pip install -e '.[dev,eval]'
ruff check app tests evals
pytest
```

PostgreSQL 集成需要已迁移的专用 `TEST_DATABASE_URL`，否则跳过。CI 配置为 Python 3.11 + PostgreSQL 16；Java 21 的 MCP 测试在兄弟目录执行。真实模型评测与普通 CI 分开，命令和 fixture 说明见 [Eval README](evals/README.md)。

PR #20 的历史 CI 为 Python 88 项、Java 10 项通过；新文档不会把该历史结果描述为今天新跑的模型验收。当前缺口统一见[生产验收清单](docs/PRODUCTION_READINESS.md)。
