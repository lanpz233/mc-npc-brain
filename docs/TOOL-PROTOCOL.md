# NPC Tool 协议

协议版本：`npc-tool/v1`

内部工具系统、HTTP 适配器、MCP 适配器必须复用同一协议与同一个 `ToolDispatcher`。

## 请求

```json
{
  "protocol": "npc-tool/v1",
  "request_id": "uuid",
  "caller": {
    "type": "DIALOG_MODEL",
    "id": "qwen-9b",
    "npc_id": "entity-uuid",
    "player_uuid": "player-uuid-or-null"
  },
  "tool": "npc.status.get",
  "arguments": {}
}
```

对应 Java 结构：`ToolRequest`。

| 字段 | 必填 | 说明 |
|---|---|---|
| `protocol` | 是 | 固定 `npc-tool/v1`；版本不符直接拒绝 |
| `request_id` | 建议 | 幂等键的一部分；为空时不缓存 |
| `caller.type` | 是 | `DIALOG_MODEL` / `BEHAVIOR_MODEL` / `INTERNAL` / `PLUGIN` / `CONSOLE` / `HTTP` / `MCP` |
| `caller.id` | 是 | 模型名、客户端 ID 等 |
| `npc_id` | 是 | 目标 NPC 实体 UUID |
| `player_uuid` | 否 | 有玩家上下文时填写 |
| `tool` | 是 | 规范名或 wire 名 |
| `arguments` | 否 | JSON 对象；按工具 Schema 严格校验 |

## 响应

```json
{
  "protocol": "npc-tool/v1",
  "request_id": "uuid",
  "success": true,
  "status": "ACCEPTED",
  "task_id": "task-uuid",
  "error_code": null,
  "error_message": null,
  "data": {}
}
```

对应 Java 结构：`ToolResult`。

- `success`：请求被正常处理且没有协议/权限错误。
- `status`：最终状态；持续行动需结合 `task_id` 查询。
- `data`：查询结果或行动接受时的摘要。

### 状态语义

| status | success | 含义 |
|---|---|---|
| `COMPLETED` | true | 查询返回成功，或即时/持续行动最终成功 |
| `ACCEPTED` | true | 持续行动已创建任务，尚未结束 |
| `RUNNING` | true | 持续行动执行中（任务查询结果） |
| `DUPLICATE` | true | 相同 request_id 的重复请求，未再次执行 |
| `FAILED` | false | 行动或查询最终失败 |
| `REJECTED` | false | 协议/参数/权限拒绝，未执行 |
| `CANCELLED` | false | 任务被取消 |
| `TIMEOUT` | false | 任务超时 |

**请求接受成功 ≠ 行动最终成功**。例如 `npc.move_to` 首次返回 `ACCEPTED`，最终结果必须通过 `npc.task.get` 获取。

## 错误码

| error_code | 场景 |
|---|---|
| `UNKNOWN_TOOL` | 工具不存在 |
| `INVALID_REQUEST` | 协议版本错误、缺少 NPC/玩家上下文、参数不是 JSON 对象 |
| `INVALID_ARGUMENT` | 必填缺失、类型错误、范围越界、枚举不匹配、未知字段 |
| `NPC_NOT_FOUND` | NPC 不存在或实体失效 |
| `CALLER_NOT_AUTHORIZED` | 高风险工具缺少神使权限 |
| `TOOL_NOT_ALLOWED` | 调用方不在工具白名单，或对话状态下禁止该工具 |
| `NPC_BUSY` | 该 NPC 已有进行中的行动任务 |
| `TASK_NOT_FOUND` | 任务不存在或不属于该 NPC |
| `TASK_CANCELLED` | 任务已取消 |
| `TASK_TIMEOUT` | 任务超时 |
| `INTERNAL_ERROR` | 工具或任务执行异常 |

## 参数校验

在 `ToolSchema` 中声明，进入工具实现前完成：

- 必填字段检查；
- 类型检查（`string` / `integer` / `number` / `boolean`）；
- 整数性检查（`1.5` 不能当 integer）；
- 数值范围（`min` / `max`）；
- 字符串最大长度与枚举白名单；
- 未知字段一律拒绝。

校验失败返回 `REJECTED + INVALID_ARGUMENT`，错误详情在 `error_message`，工具不会执行。

## 幂等

- 幂等键：`caller.type | caller.id | npc_id | tool | request_id`。
- 默认缓存 5 分钟。
- 重复请求返回首次结果，`status=DUPLICATE`，不会再次执行副作用。
- `request_id` 为空时不启用缓存。

## 查询与持续行动

| 类型 | 判定 | 返回 |
|---|---|---|
| 查询 | `category=QUERY` | 主线程立即执行，`COMPLETED + data` |
| 即时动作 | `category=ACTION` 且 `taskAction()==null` | 主线程立即执行，`COMPLETED` |
| 持续行动 | `taskAction()!=null` | 创建任务，`ACCEPTED + task_id` |

持续行动：
- 同一 NPC 同时只允许一个进行中任务。
- 通过 `npc.task.get` 查询状态。
- 通过 `npc.task.cancel` 取消。
- 超时时间由工具 `defaultTimeoutMs()` 决定。

## 工具可见性与命名

- 规范名使用点号，如 `npc.status.get`。
- 模型可见名默认把点号替换为下划线，如 `npc_status_get`，避免部分 OpenAI 兼容后端拒绝点号。
- `ToolRegistry.schemasFor(callerType)` 只导出该调用方白名单内的工具。
- `ToolRegistry.schemasFor(callerType, category)` 可进一步按类别过滤；对话模型使用 `DIALOG_MODEL + QUERY`，不暴露行动工具。
- 外部 HTTP/MCP 默认不在内置工具白名单中；适配器接入时必须显式授权并复用 `ToolPolicyService`。

## 模型输出适配

两种模型的输出都在进入 Dispatcher 前归一为 `ToolRequest`：

| 来源 | 原始形态 | 适配器 |
|---|---|---|
| Qwen（对话） | OpenAI `tool_calls`，或 Hermes XML / 裸 JSON / `[动作:...]` fallback | `LLMClient` 解析 → `LlmToolAdapter.toRequest` |
| MiniCPM（行为） | 自由文本 JSON，可能带 think 块、代码围栏、未闭合字符串、内联裸参数 | `BehaviorDecisionParser.parse` → `LlmToolAdapter.toRequest` |

- 行为模型的解析失败（非法 JSON、未知 action、参数不合法）会带原因反馈给模型重试一次，最多 2 次；仍失败则交给上层回退。
- `action` 为 `idle/none/nothing/stop/wait/noop/skip` 时视为合法“不行动”，不产生请求。
- 适配层不做权限判断，也不得把模型输出中的 `caller_type` 当作真实身份。

## 线程约束

- 协议层可以在异步线程构造请求，但执行必须通过 `ToolDispatcher.dispatchAsync` 切到主线程。
- `dispatchSync` 只能在主线程调用。
- 不允许在工具实现中阻塞等待模型推理。
