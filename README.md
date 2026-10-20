# MC AI — Paper 村民 AI 插件

让 Minecraft 里的村民真正"活"起来：有独立姓名、性格、记忆、好感、恐惧、背包和交易，
能和玩家聊天、自己闲聊、对环境和事件做出反应。

- 仓库：<https://github.com/lanpz233/mc-npc-brain>
- 服务端：Paper 1.21.1 / Java 21
- 客户端：无需安装任何 mod

---

## 本次更新（v0.1.2）

### 1. 统一的 NPC 工具系统

把 AI 能做的事收进一套统一、可注册、可校验、可测试的框架，不再散落在各个 switch 里。

| 工具 | 作用 |
| --- | --- |
| `npc.status.get` | 查询 NPC 姓名、职业、血量、坐标、需求、恐惧、好感、当前任务 |
| `npc.inventory.get` | 查询 NPC 背包、动态价格、在售商品（含附魔） |
| `world.observe.entities` | 查询 NPC 周围真实实体（玩家 / NPC / 怪物 / 动物） |
| `npc.task.get` | 查询持续行动任务的状态与进度 |
| `npc.task.cancel` | 取消一个持续行动任务 |
| `npc.move_to` | 让 NPC 走到指定坐标（低风险行动，带距离/高度校验） |

统一请求协议 `npc-tool/v1`：

```json
{
  "protocol_version": "npc-tool/v1",
  "request_id": "uuid",
  "caller": "dialog_model | behavior_model | http | console",
  "npc_id": "uuid",
  "tool": "npc.move_to",
  "arguments": { "x": 100, "y": 64, "z": 105 },
  "success": true,
  "status": "ACCEPTED",
  "task_id": "uuid",
  "error_code": null,
  "error_message": null,
  "data": {}
}
```

- 参数在分发前按 schema 校验，未知工具、非法参数、越权调用直接拒绝；
- 查询工具同步返回；持续行动走任务状态机 `ACCEPTED → RUNNING → COMPLETED / FAILED / CANCELLED / TIMEOUT`；
- 同一 `request_id` 的结果缓存 5 分钟，重复请求不会重复执行副作用；
- 所有 Bukkit 世界读写都在 Paper 主线程，模型调用全部异步，不阻塞服务器 tick；
- 详细设计见 [`docs/ARCHITECTURE-TOOLS.md`](docs/ARCHITECTURE-TOOLS.md) 与 [`docs/TOOL-PROTOCOL.md`](docs/TOOL-PROTOCOL.md)。

### 2. 双模型架构

| 角色 | 默认模型 | 职责 |
| --- | --- | --- |
| 对话模型 | `Qwen3.5-9B-Uncensored-Q4_K_M:latest` | 人格表达、聊天、意图理解、台词 |
| 行为模型 | `minicpm-act:latest` | 只看游戏状态，选择下一步动作（不说话） |

- 两个模型共用同一套服务端权威状态与安全执行机制，不会各自维护互相冲突的游戏事实；
- 行为模型不假设原生 tool calling 可靠：优先走 Ollama 原生 `/api/chat` 并关闭思考，
  输出经 `BehaviorDecisionParser` 容错解析（裸 JSON、内联参数、think 块、代码围栏、短名 `move_to` → `npc.move_to`）；
- 解析失败会自动带上错误原因重试一次，仍失败则回退原对话模型路径，不影响服务器运行；
- 行为模型默认只接自主行动（`llm.behavior.autonomy-enabled`），不影响玩家对话。

### 3. 索要 / 交换物品规则

**F 键打开 NPC 背包后：**

- **左键**：索要该物品 **1 个**；
- **右键**：用 **主手整组物品** 与 NPC 交换该物品 **1 个**。

点击不再"静默塞东西"或"先给货再说话"，统一变成一次真实交流：

1. 关闭 GUI，NPC 进入对话状态并转向玩家；
2. 代码层把双方物品、数量、好感态度注入提示，由 NPC 的模型判断；
3. **模型明确同意才执行交付**：
   - 索要：模型调用 `give_item`，或台词明确说「给你 / 拿去 / 送你了 / 拿着 / 收下」，代码层立即从 NPC 库存交付；
   - 交换：模型调用 `accept_swap` 后，一次性完成"扣玩家主手 → 扣 NPC 库存 → NPC 物品进玩家背包 → 玩家物品进 NPC 库存"；
4. 模型拒绝 → 双方物品都不动；交换中途主手物品变了、库存不足，则整体取消，不会只扣一边。

好感仍然影响 NPC 的态度（好感越高越愿意给），但不再由代码层绕过台词直接拍板，
从流程上消除"嘴上说给你、背包里却没有"和"嘴上拒绝、东西已经到手"。

### 4. 问题修复

- 修复 `LLMClient` 静态字段初始化顺序导致的 `ExceptionInInitializerError`：
  该异常会让插件在 `onEnable` 阶段被 Paper 禁用，表现为右键无反应、村民没名字、`/ai` 全部不可用；
- 新增静态初始化回归测试，避免以后调整字段顺序再次炸服；
- 模型输出中的新工具名（如 `npc_move_to`）已并入 fallback 解析，不再出现"调用丢失、JSON 原文进气泡"。

---

## 目录结构

```text
src/main/java/com/howard/mcai/     插件源码
  ai/                              LLM 客户端、工具适配、双模型、prompt
  tool/                            统一工具框架（注册/分发/策略/任务/状态）
  planner/                         中央计划器、需求层、社交层
  mob/                             村民状态、记忆、情绪、需求、背包
  gui/ listener/ security/ ...     GUI、事件、权限与世界操作
src/main/resources/                插件描述、默认配置、内置数据副本
src/test/java/                     单元测试（不需要 Bukkit 服务器）
data/                              知识库 JSON（物品/配方/职业/姓名/人格标签）
docs/                              架构与协议文档
gradle/wrapper/                    Gradle Wrapper（构建用，已随仓库提供）
gradlew.bat / gradlew              构建脚本
```

> `data/` 是数据的唯一可信源；`src/main/resources/` 里的 JSON 是打进 jar 的副本。
> 改数据时先改 `data/`，再同步到 `src/main/resources/`。

---

## 环境要求

- **JDK 21**（构建与运行都需要）
  - Windows 推荐 [Temurin 21](https://adoptium.net/zh-CN/temurin/releases/?version=21) `.msi`
  - 确认：`java -version` 输出 `21.x`
- **不需要单独安装 Gradle**：仓库自带 Gradle Wrapper，第一次构建会自动下载匹配版本
- 运行服务器：Paper 1.21.1
- 模型服务：Ollama（默认 `http://127.0.0.1:11434`）

---

## 构建 jar

Windows（PowerShell / CMD）：

```bat
gradlew.bat build
```

Linux / macOS：

```bash
chmod +x gradlew
./gradlew build
```

- 首次构建会下载 Gradle 与 Paper API 依赖，需要联网，耐心等待；
- 产物固定为 `build/libs/MC-AI.jar`（已把 Gson 重定位并打进 fat jar，可直接丢进服务器）；
- 只跑测试：`gradlew.bat test`；
- 清理重建：`gradlew.bat clean build`。

---

## 安装到服务器

1. **完全停止** Paper 进程（不要用 `/reload` 热更新插件）；
2. 把 `build/libs/MC-AI.jar` 复制到服务器 `plugins/`；
3. 确认 `plugins/` 里没有旧版本或其他同名 jar；
4. 启动服务器，看到以下日志即成功：

```text
[MC-AI] 工具系统: 已注册 6 个工具
[MC-AI] MC-AI enabled!
Done (xx.xxx s)!
```

> Windows 注意：运行中覆盖 jar 可能触发 `.paper-remapped` 文件锁，导致下次启动加载失败。
> 若出现异常，先完全停服，删除服务器根目录的 `.paper-remapped/`，再启动。

---

## 配置

首次启动生成 `plugins/MC-AI/config.yml`，改完执行 `/ai reload`（后端/模型配置）或重启服务器。

### 对话模型 + 行为模型

```yaml
llm:
  # 对话模型（OpenAI 兼容接口；可只填域名或到 /v1，插件会自动补全路径）
  url: 'http://127.0.0.1:11434/v1/chat/completions'
  api-key: ''                       # 本机 Ollama 留空即可
  model: 'Qwen3.5-9B-Uncensored-Q4_K_M:latest'
  connect-timeout-ms: 5000
  read-timeout-ms: 60000            # 本地大模型冷启动较慢，建议 ≥ 60 秒
  max-tokens: 1024
  reasoning-effort: 'none'          # Ollama 下关闭 Qwen 思考；云端不支持则留空
  keep-alive: '60m'                 # 仅 url 指向本机时发送

  # 行为模型（只选动作，不说话）；url/api-key 留空 = 复用上面的对话后端
  behavior:
    url: 'http://127.0.0.1:11434'
    api-key: ''
    model: 'minicpm-act:latest'
    max-tokens: 256
    autonomy-enabled: false         # true = 需求驱动的自主行动交给行为模型
```

模型名填 `auto` 时插件会自动探测 Ollama 当前加载的模型（30 秒缓存）；
**行为模型不能填 `auto`**，必须显式指定，避免探测到对话模型。

拉取模型：

```bash
ollama pull qwen3.5-9b-uncensored-q4_k_m
ollama pull minicpm-act
ollama list
```

### 其他常用配置

| 配置 | 说明 |
| --- | --- |
| `tick.interval-ticks` | 表现层 tick（默认 60 = 3 秒） |
| `social.ambient-chat-*` | NPC 自动闲聊开关、半径、间隔 |
| `social.group-chat-*` | 玩家群体聊天开关与半径 |
| `needs.autonomy-enabled` | 需求驱动的自主行动总开关 |
| `memory.max-chat-history-tokens` | 记忆部分 token 预算 |
| `debug.enabled` | 调试信息是否发到玩家聊天框（默认只进控制台） |

---

## 游戏内使用

### 与村民互动

| 操作 | 效果 |
| --- | --- |
| 右键村民 | 进入对话（NPC 站定并持续面向你） |
| 直接聊天框说话 | 继续当前对话；附近未被点名的 NPC 只做附和 |
| **F** | 打开该 NPC 的背包 GUI |
| **Shift + 右键** | 原版交易面板（砍价后价格会立即刷新） |
| 背包内 **左键** 物品 | 索要 1 个，等 NPC 表态后决定是否交付 |
| 背包内 **右键** 物品 | 用主手整组物品交换 1 个，由 NPC 判断值不值得 |

### 管理命令

| 命令 | 说明 |
| --- | --- |
| `/ai tool list` | 列出所有已注册工具 |
| `/ai tool <工具名> <村民名> [JSON]` | 绕过模型直接调用工具（验收/排查） |
| `/ai behavior <村民名> [提示]` | 让行为模型基于真实状态选一步动作 |
| `/ai spawn <名字>` | 生成一个 AI 村民 |
| `/ai list` | 列出当前管理的 NPC |
| `/ai llm` | 查看当前对话后端与模型 |
| `/ai llm set <url> <model> [api-key]` | 运行时切换后端 |
| `/ai llm model <模型名\|auto>` | 运行时切换模型 |
| `/ai reload` | 重载配置与人格标签库 |

示例：

```text
/ai tool npc.status.get 汤孟和
/ai tool npc.inventory.get 汤孟和
/ai tool world.observe.entities 汤孟和 {"radius":16,"limit":10}
/ai tool npc.move_to 汤孟和 {"x":100,"y":64,"z":105}
/ai tool npc.task.get 汤孟和 {"task_id":"<上一步返回的 task_id>"}
/ai tool npc.task.cancel 汤孟和 {"task_id":"<task_id>"}
/ai behavior 汤孟和 走到玩家身后三格
```

工具名也可以写模型侧的短名（如 `npc_move_to`），分发层会归一化。

### 验收要点

- 未知工具 / 缺参数 / 参数越界 / 越权调用必须返回失败且不改变世界；
- `npc.move_to` 应返回 `ACCEPTED` 和 `task_id`，到位后 `COMPLETED`，取消后 `CANCELLED`；
- 对话状态下 NPC 不得移动，已开始的移动任务应自动取消；
- 模型离线时服务器仍能正常运行，只记录 LLM 调用失败；
- 索要/交换的台词必须与实际物品转移一致。

---

## 常见问题

**插件显示红色 / `/ai` 提示 plugin is disabled？**
看 `logs/latest.log` 里 `Enabling MC-AI` 后的第一条异常。最常见原因是装了瘦包（缺依赖）
或旧 jar 没删干净。请使用 `build/libs/MC-AI.jar`，并确保 `plugins/` 只有一个 MC-AI jar。

**村民没有名字、右键无反应？**
同上，先确认插件是否 `enabled`。

**模型请求超时或返回空？**
确认 Ollama 正在运行、模型已下载；本地模型冷启动可能超过 30 秒，把 `llm.read-timeout-ms` 调到 60000。
行为模型请确认存在 `minicpm-act:latest`，且没有把 `llm.behavior.model` 写成 `auto`。

**砍价后价格没变？**
GUI 打开时砍价会自动关闭并重开以刷新价格；若仍未刷新，确认 `dynamicPrices` 已写入村民状态。

---

## 开发与测试

```bat
gradlew.bat test          :: 运行单元测试（工具框架、解析器、静态初始化等）
gradlew.bat build         :: 测试 + 打包
```

- 工具框架与模型解析器均为纯 Java，不依赖 Bukkit，可在无服务器环境测试；
- 实体、GUI、任务生命周期相关行为需要在 Paper 服务器上实测；
- 设计文档：`docs/ARCHITECTURE-TOOLS.md`、`docs/TOOL-PROTOCOL.md`。
