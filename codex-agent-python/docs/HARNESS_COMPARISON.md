# Codex Harness、AgentScope 与本项目：意图、上下文和记忆

[项目首页](../../README.md) · [系统接入](INTEGRATION.md)

核对日期：2026-09-21。仓库代码基线为 PR #20 合并后的 `9d2931f`，Python 锁定 `openai-codex==0.147.0`。AgentScope 部分比较的是**官方 Java v2 文档中的 ReActAgent / HarnessAgent**，不把 Python 版、旧版 API 或框架默认值混在一起。未运行跨框架性能测试，不给出速度、准确率或成本排名。

文中区分三种证据：官方文档描述上游能力；本地代码说明本项目实际接入；扩展方案是设计建议。当前官方文档可能已超过锁定 SDK 版本，新增功能必须验证后才能采用。

## 1. 先把名称和层次分清楚

| 名称 | 在这里指什么 | 与本项目关系 |
|---|---|---|
| 模型 | 根据输入上下文生成回答、工具调用或澄清 | 理解业务意图和选择下一步的核心来源 |
| Harness | 围绕模型的执行环境：循环、工具、状态、权限、上下文等 | 一种职责层次，不专属于某个框架 |
| Codex Harness | Codex 的 Agent 运行机制 | 本项目选用的运行时 |
| Codex App Server | 将 Codex 能力以协议暴露给集成方 | 本项目通过 Python SDK 控制，内部使用 stdio，不直接暴露给前端 |
| Codex SDK | 与 Codex 通信的编程接口 | SDK 不是第二套 Agent 循环；版本锁定并做适配 |
| AgentScope Java | Java Agent 开发框架，含 ReActAgent 与 HarnessAgent | 可作为另一种运行时路线；本仓库未接入 |
| 本项目 | 基于 Codex 的业务服务和安全/可靠性工程基线 | 增加企业接口、会话归属、审批、业务 Adapter 和评测 |

官方将 App Server 定位为产品集成接口；Thread 是会话，Turn 是一次执行，Item 是执行中的消息或工具活动。参见 [Codex App Server](https://learn.chatgpt.com/docs/app-server)。AgentScope HarnessAgent 在 ReActAgent 上组合工作区、状态、记忆、压缩等能力，参见 [Harness 架构](https://java.agentscope.io/v2/zh/docs/harness/architecture)。

## 2. 意图识别：谁决定查订单、取消订单或追问

**本项目没有独立的意图分类器。** 从 `AgentService.chat/open_stream` 到 `CodexRuntime.run_turn/stream_turn`，用户消息被交给同一个业务 Agent。模型结合开发指令、已有上下文和工具描述决定下一步；Harness 负责执行工具调用并把结果交回后续推理。

例如用户说：“订单 1001 怎么还没到？能取消的话帮我取消。”期望行为是：查询实时订单 → 根据事实与业务规则判断 → 必要时请求审批 → 获批后再发起受控操作。工具缺参数时追问；没有退款工具就不能声称已经退款。这些是**需要通过题库验收的行为目标**，不是模型能力的确定性保证。

| 层次 | 本例中负责什么 | 不能替代什么 |
|---|---|---|
| 模型 | 理解查询/取消意图，抽取 orderId，选择工具或追问 | 登录认证和业务授权 |
| Skill / Tool 描述 | 说明先查事实、取消条件、参数语义和禁止事项 | 程序中的强制门禁 |
| Harness | 组织模型与工具往返，管理执行与上下文 | 公司自己的售后规则 |
| Adapter / ExecutionService / OMS | 验身份、审批、参数、幂等和事务 | 不能依赖模型说“我有权限” |

AgentScope 的 ReActAgent 同样由模型与 Toolkit 协作完成推理/行动，不是必须先执行一个“意图分类”模块；需要固定标签路由时，可额外使用结构化输出或业务路由器。[AgentScope 智能体](https://java.agentscope.io/v2/zh/docs/building-blocks/agent)

**选择建议**：当前工具数量少，先把澄清、工具选择与权限题库跑通。跨订单、合同、财务等业务域的分流确实复杂时，再增加入口分类器，定义允许的标签、无法判断时的澄清路径和路由评测；不要把分类器的 confidence 当安全凭证。

## 3. 上下文管理：本轮能看到什么

“上下文”不是一个单独的数据库，而是本轮模型可用的信息：指令、用户输入、会话消息、工具结果，以及在预算内保留的摘要等。

本项目实际路径：

- 创建会话：`create_thread` 注入宿主读取的订单 Skill，返回 Thread ID。
- 保存映射：PostgreSQL 记录业务 conversation、user、tenant 与 Thread 的关联。
- 继续对话：`_resolve_owned` 先验归属，`_resume_thread` 恢复同一 Thread，并重新注入当前可信 MCP 身份与运行限制。
- 运行一轮：普通调用使用 `thread.run`，流式使用 `thread.turn`；没有每次从业务数据库拼接完整聊天历史。
- 压缩：`compact_thread` 发起压缩，并等到新压缩 Turn 完成；仅收到 RPC 确认不能算完成。

代码入口：[AgentService](../app/services/agent_service.py)、[CodexRuntime](../app/runtime/codex_runtime.py)、[依赖装配](../app/core/lifespan.py)。Skill 在服务启动时读取，显式注入发生在创建 Thread；当前没有把修改后的 Skill 自动重新注入所有旧 Thread 的机制。更新 Skill 后应重启服务并用新会话评测，历史会话的策略迁移要另行设计。

Codex 提供 Thread 恢复和手动压缩接口；自动压缩阈值由模型/配置决定，官方配置项是 `model_auto_compact_token_limit`，未设置时使用模型默认值。不能写死“固定聊 N 轮就压缩”。[App Server](https://learn.chatgpt.com/docs/app-server)、[配置参考](https://learn.chatgpt.com/docs/config-file/config-reference)

压缩是上下文预算管理，会有信息损失风险。订单金额、审批结论、权限和提交结果必须保存在权威系统中，需要时重新查询，不能只靠摘要记住。`thread/resume` 也不等于自动重放未完成的业务事务。

## 4. 记忆：至少分成四类

| 类型 | 例子 | 本项目状态 / 归属 |
|---|---|---|
| 当前会话上下文 | “刚才那个订单”指 1001 | 交给 Codex Thread；仍需处理歧义和压缩损失 |
| 会话持久化与恢复 | 服务重启后继续同一 conversation | PostgreSQL 映射 + 专属 CODEX_HOME；恢复演练待完成 |
| 跨会话长期记忆 | 客户长期偏好、已验证的处理习惯 | 未实现企业租户隔离的 Memory 服务 |
| 业务事实与知识 | 实时订单、退款记录、政策手册 | OMS / 知识服务；知识检索可通过 MCP 接入 |

**Codex 本身并不是“没有长期记忆”。** 当前官方文档描述：启用本地 memories 后，Codex 从符合条件的历史会话在后台提取记忆，保存在 CODEX_HOME 下；新会话可利用这些信息。它与 ChatGPT 网页版记忆是不同的存储和控制体系，也不是每结束一轮立即更新。[Codex Memories](https://learn.chatgpt.com/docs/customization/memories)

官方当前配置参考中 `features.memories` 默认关闭，并有 `memories.generate_memories` / `memories.use_memories` 等控制项。**这是当前上游文档，不是本仓库已经完成的功能验收。** [配置参考](https://learn.chatgpt.com/docs/config-file/config-reference)

本项目没有记忆读写 API、业务命名空间、删除策略或租户隔离测试；`business_runtime_config()` 也没有显式固定 memory 开关。不能因为没有自建 Memory 类，就声称任何外部 CODEX_HOME 配置下都绝不会使用上游记忆。

**当前部署要求**：使用干净、专属、受管理的 CODEX_HOME，不启用未经验证的跨会话记忆；上线前核对锁定 CLI 的有效配置及记忆隔离。共用目录里的个人化记忆不能直接当多租户企业记忆库。若需要该能力，应由独立 Memory 服务按可信 tenant/user 授权，并记录来源、时效、可删除性及审计；检索结果作为参考信息，不能授予操作权限。这是建议的后续扩展。

## 5. AgentScope 的对应机制

AgentScope Java 将两种状态分开：`RuntimeContext` 是每次调用的身份和临时依赖；`AgentState` 是会话消息、摘要、权限等可恢复状态，由 `AgentStateStore` 持久化。设置 `(userId, sessionId)` 决定状态槽位；它们本身不是已验证的租户身份，应用仍要认证、派生并隔离。[上下文与 AgentState](https://java.agentscope.io/v2/zh/docs/building-blocks/context)

HarnessAgent 提供可配置摘要压缩、大工具结果卸载及溢出恢复。当前文档明确：压缩和工具结果卸载需显式配置，不能把框架具备能力写成默认全开。[上下文压缩](https://java.agentscope.io/v2/zh/docs/harness/compaction)

长期记忆采用日记录与汇总 MEMORY.md 两层：从会话提取，再合并，供之后调用使用；记忆提炼与当前上下文压缩是不同操作，可以分别配置。文件/存储隔离仍要结合实际工作区部署验证。[AgentScope 记忆](https://java.agentscope.io/v2/zh/docs/harness/memory)

## 6. 同一层次的能力对照

| 维度 | Codex Harness / App Server | AgentScope Java v2 | 本项目已经接入 |
|---|---|---|---|
| 工具推理循环 | 使用 Codex 运行机制 | ReActAgent；HarnessAgent 组合工程能力 | Codex SDK 薄适配 |
| 意图处理 | 模型结合指令/上下文/工具推理 | 模型结合 sysPrompt/上下文/Toolkit 推理 | 没有独立分类/多 Agent 路由器 |
| 会话表示 | Thread / Turn / Item | RuntimeContext + AgentState + 消息/事件 | 业务 conversation 映射 Thread |
| 长上下文 | 上游压缩与配置阈值 | 配置 compaction / 大结果卸载 | 手动压缩接口；未另写压缩引擎 |
| 持久化 | Codex 管理会话存储和 resume | AgentStateStore 及其后端实现 | CODEX_HOME + PostgreSQL，单 Runtime |
| 长期记忆 | 本地 memories，依启用条件和配置 | Harness 双层记忆及工具 | 未提供企业 Memory 服务 |
| 扩展方式 | 配置、指令、MCP、协议适配 | Java Builder、Middleware、Toolkit、存储接口 | Skill + MCP + Policy + 执行操作注册 |
| 模型选型 | 受 Codex provider/协议与运行时支持约束 | 通过模型抽象和 provider 扩展 | 未封装模型路由/故障切换，不保证任意模型兼容 |
| 企业审批 / 幂等 | 提供工具审批机制；业务事务仍需实现 | 提供权限/HITL机制；业务事务仍需实现 | 持久化业务审批和固定 ID，OMS 原子去重待验收 |
| 多副本 | 集成方需设计状态归属与调度 | 提供分布式状态等构件，仍须验证并发与隔离 | 当前明确仅支持单进程单副本 |

表格是上述官方能力与仓库代码的责任映射，不是完整功能清单。共享状态存储不能单独证明分布式串行、崩溃接管或业务 exactly-once；两条路线都需要业务和部署验收。

## 7. 我们应该继续用哪条路线

**基于本项目现状的建议**：继续完成 Codex 路线的真实业务验收，保留 Runtime Protocol 和业务工具契约作为复用边界。现有代码不需要再套一层 AgentScope 或 LangChain 来运行同一次推理循环。

如果公司后续有硬约束：必须全 Java、需要直接定制推理中间件、或需适配多个非 Codex 模型提供商，则用相同业务数据和验收题库做 AgentScope Java 的小范围验证，再决定是否实现另一套 Runtime Adapter。不要直接将 Codex Thread 状态当作 AgentScope AgentState 使用；运行时迁移需要新会话、数据保留和兼容策略。

评测框架与运行框架可以独立选择：本项目通过 HTTP/SSE 做 LangSmith Eval，因此选择 LangSmith 不等于选择 LangChain Runtime。公司真正应长期积累的是业务 Tool 合约、授权边界、Skill、失败 Case 与验收证据。

## 8. 与常见需求的关系

- **RAG**：取回资料供模型使用，不等于自动记住用户，更不能替代实时订单查询。
- **Workflow**：需要确定步骤、补偿、定时触发、跨天恢复时可使用业务工作流；当前 Turn 循环和审批数据库不等于持久工作流引擎。
- **微调**：可以作为后续业务质量优化手段，但不会自动补上权限、幂等、状态持久化或数据新鲜度；本项目尚未接入微调流程。
- **“超级底座”**：应理解为可持续复用、可验证、边界明确的工程能力；并不意味着第一版就内置所有数据库、网关、模型、Agent 和调度器。
