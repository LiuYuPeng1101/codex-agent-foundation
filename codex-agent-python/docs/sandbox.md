# 订单 Agent 的本地权限边界

[运行配置](../README.md#运行安全与联调配置) · [执行授权](EXECUTION_CONTRACT.md)

当前 Runtime 仅接受 `READ_ONLY`；创建、恢复 Thread 和执行 Turn 均设置对应沙箱。业务写操作通过 MCP → ExecutionService → OMS 实现，不通过本地文件或 Shell 执行。

## 三种不同的边界

| 边界 | 本项目的作用 |
|---|---|
| 本地 Sandbox / 能力策略 | 限制本地文件写入与 Shell/JS/浏览器等能力 |
| 持久化业务审批 | 判断规范业务动作是否批准，并签发固定执行 ID |
| OMS 最终权限与事务 | 校验租户、资源、状态和幂等后执行真实操作 |

三者不能相互替代。只读沙箱不禁止 MCP 对外调用；允许调用 MCP 工具也不表示获得订单写权限。

## 当前实现与限制

- `app/runtime/policy.py` 限制本地工具，`launcher.py` 清理传入 Codex 的宿主环境。
- 订单 Skill 由宿主读取并注入新 Thread，不依赖模型通过 Shell 读取文件。
- 某些模型仍可暴露 apply_patch；READ_ONLY 和拒绝提权回调是写入边界的一部分。
- Docker 内容目录为 `/agent`，CODEX_HOME 为专属持久目录；文件与网络的实际可达性需要部署层验收。
- 这套模式不提供运行任意不可信代码的 OS 级隔离。需要 Shell 的业务应另设计运行身份、文件和网络隔离；直接切到 WORKSPACE_WRITE/FULL_ACCESS 会被当前 Runtime 拒绝。

## 正确的验收方式

在专用测试环境验证：请求通过 Shell/脚本修改订单、本地写文件、读取非授权秘密、访问其他租户状态时不能成功；正常订单读取仍能通过 MCP 完成。使用实际文件/网络/OMS 审计结果判断，不能只看模型口头拒绝。

旧文档的“让 Agent 读取 README 第一行”不再作为本模式的成功用例：READ_ONLY 是权限上限，不保证已注册任意文件读取工具。需要测试一般文件 Agent 时，另建隔离场景。

详见[上线验收清单](PRODUCTION_READINESS.md)和[可靠性说明](RELIABILITY.md)。
