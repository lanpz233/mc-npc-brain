# NPC Tool System 架构

本文档只描述**当前代码中已实现**的工具系统。Qwen 9B / MiniCPM-1B 双模型接入、HTTP API、MCP 适配器尚未实现。

## 目标

- 所有 AI 可调用能力经过统一的注册、校验、权限和分发入口。
- 新增工具只新增实现类并注册，不再修改核心调用入口。
- 查询工具与持续行动任务共享同一套请求/结果/错误协议。
- 内部工具系统与外部协议适配层解耦。
- 模型不能绕过权限直接修改世界；主线程不等待模型推理。

## 分层

```text
对话模型 / 行为模型 / 插件命令 / 未来 HTTP·MCP
                    │
                    ▼
              ToolDispatcher
  协议校验 → 工具查找 → 参数校验 → ToolPolicyService
                    │
          ┌─────────┴─────────┐
          ▼                   ▼
    查询/即时工具         NpcTaskManager
   （主线程直接执行）    （每秒 tick，超时/取消）
          │                   │
          └─────────┬─────────┘
                    ▼
            GameStateProvider / NpcTool 实现
                    │
                    ▼
         真实 Paper 世界与 VillagerState
```

## 组件职责

| 类 | 职责 |
|---|---|
| `tool.NpcTool` | 工具接口：名称、类别、风险、Schema、可见调用方、即时执行或任务体 |
| `tool.ToolSchema` | 参数声明、类型/范围/必填/枚举/长度校验、OpenAI function schema 导出 |
| `tool.ToolContext` | 可信调用上下文：调用方类型、NPC、玩家、请求 ID |
| `tool.ToolRequest` | 统一请求结构与协议版本 |
| `tool.ToolResult` | 统一响应：success、status、task_id、error、data |
| `tool.ToolRegistry` | 注册、重复名称拒绝、规范名/wire 名查找、按调用方导出 schema |
| `tool.ToolPolicyService` | 调用方白名单、NPC 存在性、玩家上下文、对话移动锁、高风险等级 |
| `tool.ToolDispatcher` | 协议/参数/权限/幂等校验，查询直接执行，持续行动创建任务 |
| `tool.NpcTask` | 单个行动任务的状态机 |
| `tool.NpcTaskAction` | 持续行动的 start/tick/onCancel |
| `tool.NpcTaskManager` | 任务冲突检查、每秒 tick、超时、取消、结果保留与关服清理 |
| `tool.GameStateProvider` | 服务端权威状态读取接口 |
| `tool.BukkitGameStateProvider` | 主线程 Bukkit 实现，复用 `VillagerState` / `PromptBuilder` 数据 |
| `tool.tools.*` | 具体内置工具实现 |

## 调用链

### 插件内部 / 调试命令

```text
/ai tool <tool> <npc> [json]
  → ToolRequest.create(PLUGIN, ...)
  → ToolDispatcher.dispatchSync()
  → 主线程执行
```

### 异步适配层（未来 HTTP/MCP）

```text
异步线程
  → ToolDispatcher.dispatchAsync()
  → 注入的主线程执行器（Bukkit.getScheduler().runTask）
  → dispatchSync()
  → CompletableFuture 返回
```

Dispatcher 本身不引用 Bukkit，便于单元测试；Bukkit 主线程执行器由 `McaiPlugin` 注入。

### 任务推进

`CentralPlanner` 每秒心跳末尾调用 `NpcTaskManager.tick()`，推进所有进行中的任务并处理超时/清理。

## 已实现工具

| 规范名 | 类别 | 风险 | 说明 |
|---|---|---|---|
| `npc.status.get` | QUERY | LOW | NPC 血量、职业、位置、开放度、情绪、需求、对话状态、LLM 忙闲 |
| `npc.inventory.get` | QUERY | LOW | 背包、动态价格、已上架商品 |
| `world.observe.entities` | QUERY | LOW | 半径 ≤32、最多 64 条实体快照 |
| `npc.task.get` | QUERY | LOW | 查询单个任务或该 NPC 的任务列表 |
| `npc.task.cancel` | ACTION（即时） | LOW | 取消指定任务或该 NPC 全部进行中任务 |
| `npc.move_to` | ACTION（持续） | LOW | 同世界移动，返回 task_id；对话中禁止启动 |

现有 18 个旧工具（`say`、`give_item`、`break_block` 等）仍走 `CentralPlanner.executeDecision` 的原路径，尚未迁移到本框架。迁移将在后续阶段逐项进行。

## 任务生命周期

```text
ACCEPTED ──start()──► RUNNING ──tick()──► COMPLETED
    │                    │
    │                    ├──► FAILED
    │                    ├──► CANCELLED（npc.task.cancel / 对话占用 / 关服）
    │                    └──► TIMEOUT（defaultTimeoutMs）
    └── start() 内立即完成/失败
```

- 同一 NPC 同时最多一个进行中任务；冲突返回 `NPC_BUSY`。
- 已完成任务保留 5 分钟，供 `npc.task.get` 查询；之后自动清理。
- `npc.move_to` 在 NPC 进入对话时自动取消并清零速度，保持“对话站立不动”约束。

## 线程模型

- `GameStateProvider` 所有读取强制主线程，异步调用直接抛异常。
- `ToolDispatcher.dispatchSync` 必须在主线程调用；`dispatchAsync` 负责切主线程。
- 任务 `start/tick/onCancel` 全部由主线程心跳调用。
- 模型调用仍保持异步 HTTP，回调后切回主线程，不在主线程等待推理。
- 幂等缓存使用 `ConcurrentHashMap`，避免重复请求重复执行副作用。

## 如何新增工具

1. 在 `tool.tools` 下实现 `NpcTool`。
2. 查询/即时工具实现 `execute`；持续行动实现 `taskAction()`。
3. 在 `McaiPlugin.registerBuiltinTools()` 注册一行。
4. 单元测试校验参数与策略；实体相关行为在服务器上验证。

不需要修改 `ToolDispatcher`、`CentralPlanner` 的执行分支。

## 双模型适配（阶段五）

两个模型共用同一套服务端状态与执行闸门，只在“看得见什么、怎么输出”上不同。

| 角色 | 模型 | 可见工具 | 输出形态 | 执行入口 |
|---|---|---|---|---|
| 对话 | Qwen 9B | 旧 18 工具 + 统一注册表中的 QUERY 工具 | 原生 `tool_calls`（Hermes/裸 JSON 作为 fallback） | `CentralPlanner`：旧工具走原 switch，统一工具走 `ToolDispatcher` |
| 行为 | MiniCPM-1B | 统一注册表中 `allowedCallers` 含 `BEHAVIOR_MODEL` 的工具 | 单个 JSON `{"action":...,"args":{...}}` | `BehaviorModelClient` → `BehaviorDecisionParser` → `ToolDispatcher` |

- 配置：`llm.*` 为对话模型，`llm.behavior.*` 为行为模型；行为模型留空时回退对话模型配置，`model` 不允许 `auto`。
- `LlmToolAdapter` 只做名字归一（`npc_move_to` ↔ `npc.move_to`）与 `ToolRequest` 包装，权限判断仍由 `ToolPolicyService` 负责。
- `BehaviorDecisionParser` 负责吸收 1B 模型的坏格式：think 块/代码围栏剥离、括号栈提取 JSON、内联裸参数修复；解析失败返回原因并由客户端追问一次。
- 实测 MiniCPM 原生 `tool_calls` 不可靠、`format:"json"` 更易吐非法 JSON、开启 thinking 时正文为空，因此行为模型固定走 Ollama 原生 `/api/chat` 且 `think:false`，非 Ollama 后端回退 `/v1/chat/completions`。
- 对话模型只暴露 QUERY 统一工具：行动仍由旧工具集或行为模型执行，避免绕过“对话中站立不动”约束。
- 需求驱动的自主行动由 `llm.behavior.autonomy-enabled` 控制；开启后走行为模型 + Dispatcher，解析失败自动回退原对话模型路径。

## 尚未实现

- 旧 18 个工具的迁移。
- HTTP API、MCP 适配器、职业工具包、经济系统工具。
- 行为模型输出中的台词（当前行为模型只选动作，不说话）。
