# 上手指南：从空环境到第一次真实查询

[返回项目首页](../../README.md) · [接入现有系统](INTEGRATION.md) · [验收清单](PRODUCTION_READINESS.md)

以下命令使用 Bash，适用于 Linux、macOS 或云端开发环境；Windows 用户可以直接使用云端 Linux 终端，不要求安装 WSL。需要 Python 3.11+、JDK 21、Maven、Docker 和 `psql` 客户端。已有专用 PostgreSQL 时可省去 Docker，修改连接配置即可。

这里分两级验证：**代码测试不需要模型账号**；**真实查询需要可用的模型认证，会产生模型调用费用**。提供的 OMS fixture 仅支持读取，不能验证真实订单写入。

## 1. 安装项目

在仓库根目录执行；未克隆时先执行首页的 clone 命令。

```bash
cd codex-agent-python
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -e '.[dev,eval]'
```

如果 `.venv` 来自别的机器或已失效，先移走旧目录再创建。`openai-codex==0.147.0` 在项目中锁定，CLI 由该包配套提供；不要随手改成最新版本，否则私有 SDK 适配与事件契约需要重新验收。

## 2. 生成本机专用测试配置

仍在 `codex-agent-python/`。下面生成已被 Git 忽略的 `.env`，不覆盖已有文件。密钥随机生成且不打印；该文件仅适用于隔离开发环境，生产通过密钥管理服务注入。

```bash
python - <<'PY'
import os
from pathlib import Path
import secrets
import shlex

api, mcp, execution, fixture, oms = [secrets.token_hex(32) for _ in range(5)]
values = {
    "APP_ENV": "development",
    "AGENT_WORKSPACE": str(Path.cwd()),
    "CODEX_HOME": str(Path.home() / ".local/share/codex-order-agent-dev"),
    "DATABASE_URL": "postgresql+psycopg://agent:agent_dev_only@127.0.0.1:5432/agent_runtime",
    "TEST_DATABASE_URL": "postgresql+psycopg://agent:agent_dev_only@127.0.0.1:5432/agent_runtime",
    "API_SHARED_SECRET": api,
    "ORDER_MCP_URL": "http://127.0.0.1:8080/mcp",
    "ORDER_MCP_SERVICE_TOKEN": mcp,
    "MCP_SERVICE_TOKEN": mcp,
    "EXECUTION_SERVICE_SECRET": execution,
    "EXECUTION_SERVICE_BASE_URL": "http://127.0.0.1:8000",
    "ORDER_SERVICE_BASE_URL": "http://127.0.0.1:8090",
    "ORDER_SERVICE_TOKEN": oms,
    "EVAL_FIXTURE_MODE": "test-only",
    "EVAL_FIXTURE_SECRET": fixture,
    "EVAL_OMS_SERVICE_SECRET": oms,
    "EVAL_FIXTURE_BASE_URL": "http://127.0.0.1:8090",
    "EVAL_BASE_URL": "http://127.0.0.1:8000",
    "EVAL_API_SHARED_SECRET": api,
    "EVAL_USER_ID": "dev-user",
    "EVAL_TENANT_ID": "dev-tenant",
    "EVAL_ROLES": "support.agent,agent.approver",
}
fd = os.open(".env", os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(fd, "w", encoding="utf-8") as f:
    f.write("".join(f"{k}={shlex.quote(v)}\n" for k, v in values.items()))
print("已生成本机测试 .env；未配置模型认证。")
PY
```

这份生成文件同时兼容 Bash 与 dotenv。**仅加载自己生成并信任的配置文件**；现有 `.env.example` 中有空格值，不能原样当 shell 脚本 source。

后续每个终端都先进入 `codex-agent-python/` 并执行：

```bash
. .venv/bin/activate
set -a
. ./.env
set +a
```

## 3. 准备数据库并运行无需模型的测试

全新专用开发库可用以下命令创建；端口被占用时更换映射，并同步 `.env` 及 `psql` 命令。不要对生产库执行这组初始化命令。

```bash
docker run -d --name codex-agent-pg \
  -e POSTGRES_USER=agent \
  -e POSTGRES_PASSWORD=agent_dev_only \
  -e POSTGRES_DB=agent_runtime \
  -p 127.0.0.1:5432:5432 \
  -v codex-agent-pg-data:/var/lib/postgresql/data \
  postgres:16
```

用 `docker exec codex-agent-pg pg_isready -U agent -d agent_runtime` 确认就绪后，按文件名顺序应用迁移：

```bash
(
  set -e
  export PGPASSWORD=agent_dev_only
  for migration in migrations/*.sql; do
    psql -v ON_ERROR_STOP=1 -h 127.0.0.1 -U agent -d agent_runtime -f "$migration"
  done
)
```

任何迁移报错都应停止后续启动，处理原因后继续。此循环是**全新开发库初始化**，不是生产迁移版本管理器；老库升级须核对已应用版本、备份，并按[执行契约](EXECUTION_CONTRACT.md)停旧写流量后升级，不能随意重跑或跳过历史迁移。

```bash
ruff check app tests evals
pytest
```

另一个终端进入 Java 目录执行：

```bash
cd hanress-test
mvn -B test
```

Java 测试包含完整 MCP HTTP 协议往返，但授权服务与 OMS 是测试替身。Python 的 PostgreSQL 测试会清理测试表，`TEST_DATABASE_URL` 必须指向可丢弃的测试库。

## 4. 配置 Codex 认证和模型

回到已加载 `.env` 的 Python 终端。使用项目配套 CLI 登录，确保它和服务使用**同一个 CODEX_HOME**：

```bash
mkdir -p "$CODEX_HOME"
CODEX_BIN="$(python -c 'from openai_codex import CodexConfig; from openai_codex.client import _resolve_codex_bin; print(_resolve_codex_bin(CodexConfig()))')"
"$CODEX_BIN" --version
"$CODEX_BIN" login
```

若使用 API key 认证，在终端安全输入，不写入聊天或 Git：

```bash
read -r -s -p 'OpenAI API key: ' OPENAI_API_KEY
export OPENAI_API_KEY
printf '%s' "$OPENAI_API_KEY" | "$CODEX_BIN" login --with-api-key
unset OPENAI_API_KEY
```

选一种认证方式即可；云端终端无法接收浏览器回调时，可按官方文档使用设备码登录（若账号允许），或使用 API key。账号必须具有所选模型访问权。在专属 `$CODEX_HOME/config.toml` 中设置账号可用的 `model`，不要复制个人 Codex 的整份配置、历史目录或 memory。项目没有 `MODEL_NAME` 环境变量；仅设置该变量不会选择模型。

```toml
# 将示意值替换成该账号实际可用、且已选定用于验收的模型 ID。
model = "YOUR_AVAILABLE_MODEL_ID"
```

认证方式详见[官方认证文档](https://learn.chatgpt.com/docs/auth)。项目启动会覆盖业务运行权限和 MCP 配置。第三方模型认证变量、代理变量不会自动穿过 launcher 的环境白名单；这类接入需要单独设计和测试。

## 5. 启动三个服务

打开三个终端，均先按第 2 步加载 `.env`。以下均使用本机地址，只供开发；容器/远端部署要使用服务可达地址，不能照搬 `127.0.0.1`。

**终端 A：只读测试 OMS**，留在 Python 目录：

```bash
uvicorn evals.fixture_server:create_app --factory --host 127.0.0.1 --port 8090
```

**终端 B：Java MCP Adapter**，加载配置后切目录：

```bash
cd ../hanress-test
mvn spring-boot:run -Dspring-boot.run.arguments=--server.address=127.0.0.1
```

**终端 C：Agent Service**，留在 Python 目录：

```bash
uvicorn app.main:app --host 127.0.0.1 --port 8000 --workers 1
```

初次联调不要使用 reload 或多 worker。数据库不可用、缺少迁移、Skill 路径错误或 Codex 启动失败会阻止启动。Java 的授权服务地址先配置好，第一次写工具调用时才会用到它。

在第 4 个已加载配置的 Python 终端检查：

```bash
curl --fail-with-body http://127.0.0.1:8000/api/v1/health
curl --fail-with-body http://127.0.0.1:8000/api/v1/ready
```

预期分别为 `status=ok` 和 `status=ready`。ready 不检测模型额度或 OMS 全链路可用性，还须执行下一步。

## 6. 创建会话并查询订单

以下使用测试身份。实际产品应由业务后端从登录身份生成 Header。

```bash
AGENT_URL=http://127.0.0.1:8000
AUTH_HEADERS=(
  -H "Authorization: Bearer $API_SHARED_SECRET"
  -H "X-User-Id: $EVAL_USER_ID"
  -H "X-Tenant-Id: $EVAL_TENANT_ID"
  -H 'X-Roles: support.agent'
)
CONVERSATION_ID="$(curl --fail-with-body -sS -X POST \
  "$AGENT_URL/api/v1/agent/conversations" "${AUTH_HEADERS[@]}" \
  | python -c 'import json,sys; print(json.load(sys.stdin)["conversation_id"])')"

curl --fail-with-body -sS \
  "$AGENT_URL/api/v1/agent/conversations/$CONVERSATION_ID/turns" \
  "${AUTH_HEADERS[@]}" -H 'Content-Type: application/json' \
  -d '{"message":"查询订单 1001 的真实状态。"}'
```

预期响应包含 `conversation_id` 和 `answer`。fixture 返回 `PROCESSING`；核对 Agent 确实调用了 `get_order_status`，不能只凭模型说了正确答案就认定接通。

用同一会话体验流式查询：

```bash
curl --fail-with-body -N \
  "$AGENT_URL/api/v1/agent/conversations/$CONVERSATION_ID/turns/stream" \
  "${AUTH_HEADERS[@]}" -H 'Content-Type: application/json' \
  -d '{"message":"重新查询刚才那个订单，说明查询到的事实。"}'
```

应观察 `tool.started` / `tool.completed`、`message.delta`、`turn.completed`。必须检查终态 `data.status` 是 `completed` 且无错误；流结束、HTTP 200 或出现部分文字都不等于成功。事件示例与断线处理见 [Service README](../README.md)。

## 7. 验证审批和真正的写操作

当前只读 fixture 可以验证取消请求进入 PENDING，以及拒绝后没有订单写入。**它的取消接口固定返回 501**；批准后要真正取消，必须把 Java 的 `ORDER_SERVICE_BASE_URL/TOKEN` 切换到实现了[幂等契约](EXECUTION_CONTRACT.md)的测试 OMS 并重启 Java。

完整操作顺序：

1. 发起取消请求，查询该 conversation 的 PENDING 审批。
2. 用同租户、有 `agent.approver` 权限的可信审批人确认参数并 approve/reject。
3. approve 只更新授权记录，**不会自动唤醒模型或执行订单操作**。
4. 用原申请人的身份在原会话发起后续 Turn，让 Agent 重新调用相同取消工具；审批人自己的会话不能替代原会话。
5. 在 OMS 核对固定 Idempotency-Key、订单状态、事件数量。CONSUMED 只表示执行 ID 已签发。

具体 API 及 UI 接入步骤见[系统接入指南](INTEGRATION.md)。

## 8. 运行真实评测并留存证据

沿用第 2 步的测试配置；LangSmith 另需 `LANGSMITH_API_KEY`（按账号情况配置 workspace）。

```bash
python -m evals.run
python -m evals.seed_langsmith_dataset --dataset codex-order-agent-seed
python -m evals.run_langsmith --dataset codex-order-agent-seed --experiment-prefix order-baseline
```

先核对 Java 确实指向预期 fixture/测试 OMS，再跑题库。任一跳过、错误或确定性评分不达标都不能通过门禁；模型质量 Judge 另有配置。详见 [Eval README](../evals/README.md)。

记录提交 SHA、模型 ID、依赖版本、配置摘要（不含密钥）、数据库迁移版本、实验链接和失败 Case。保存结果不代表全部上线项完成，按[验收清单](PRODUCTION_READINESS.md)逐项签收。

## 常见阻塞

| 现象 | 检查 |
|---|---|
| Python 启动提示配置缺失 | 当前目录、`.env`、三个服务密钥至少 32 字符；执行密钥必须独立 |
| Skill 文件不存在 | AGENT_WORKSPACE 应指向包含 `.agents/skills/order-analysis/SKILL.md` 的 Python 项目目录；镜像中为 `/agent` |
| PostgreSQL 缺表/缺列 | 是否按顺序完成 001–005；应用连接串使用 `postgresql+psycopg://` |
| Thread 创建或推理失败 | 配套 CLI 版本、专属 CODEX_HOME 中认证、可用模型、网络、MCP 8080 服务 |
| 401 / 400 / 404 | 服务密钥、身份 Header、会话归属、fixture 租户是否一致 |
| 409 / 503 | 同会话忙、全局容量满、数据库故障或执行结果不明；不要盲目重试写操作 |
| 取消审批后仍失败 | 是否还连接只读 fixture；execution 密钥和 Java → Python 地址是否一致 |
| Eval 有 SKIPPED | fixture 未正确配置/验证；跳过本身就阻止发布 |

本指南命令已按仓库代码核对；本文档更新没有使用真实模型凭据完成一次新的端到端验收。
