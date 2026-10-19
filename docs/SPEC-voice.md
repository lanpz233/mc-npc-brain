# MC 语音 Mod — Agent 开发文档

> **状态：搁置（2026-10-18 用户决定，优先服务端 AI 对话）**
>
> 1.21.1 Fabric 客户端 mod：给"生态大脑"项目加耳朵。玩家按住按键说话 → STT 转成文字 → 注入中央大脑 → AI 用文字回复。
> **当前范围：只做 STT 输入 + 文字回复，不做 TTS。**（TTS 后续再加）
>
> 生态大脑的中央决策架构见 `SPEC.md`，本 mod 只负责"耳朵"，复用那套大脑。

## 1. 目标（MVP）

- 玩家按住指定按键说话，松开后 1~3 秒内语音被转成文字
- 转写文字以 `[语音]玩家名: 内容` 形式显示在聊天框，并注入中央大脑的输入流
- AI 回复走文字（聊天框 / 头顶气泡），不播语音

## 2. 两档架构（都要实现，先 A 后 B）

### A 档：验证版（给自己用，跑通优先）

```
[Fabric mod] 按键录音(16kHz PCM) ──WebSocket──▶ [Python sidecar]
                                                    │
                                              faster-whisper small
                                                    │ 转写文字
                                                    ▼
                                              中央大脑 API（SPEC.md）
                                                    │ 文字回复
                                              ◀─────┘
[mod 显示] 聊天框 + 可选头顶气泡
```

- sidecar：Python + `faster-whisper`（small 中文模型）+ `websockets`，监听 `localhost:8765`
- mod 只管录音和发送，不管模型，逻辑最薄

### B 档：分发版（给普通玩家，零 Python）

**已验证结论（2026-10-18 实测）：**
- ✅ **STTA**（Modrinth，Fabric，支持 1.21–1.21.1，whisper.cpp 本地转写，GPL-3.0 开源）——**首选 fork/参考对象**。它是无障碍用途（说话→显示文字），fork 后把转写结果接到中央大脑即可。
- ❌ VoskLib / WhisperLib / Slip of a Tongue：均为 Forge 1.20.1，1.21.1 不可用，**不要踩坑**。

```
[Fabric mod] 按键录音 ──▶ [STT 二选一] ──▶ 中央大脑 ──▶ 文字回复显示
                              │
                    ① fork STTA（推荐，whisper 准度高）
                    ② 自研内嵌 Vosk Java（轻，但准度差一档）
```

## 3. 技术选型

| 组件 | 选型 | 说明 |
|---|---|---|
| MC 版本 | 1.21.1 | 用户指定 |
| Mod loader | Fabric | 轻、API 稳定，1.21.1 支持好 |
| Java | 21 | MC 1.21 强制要求 Java 21，agent 写构建配置时注意 |
| 按键绑定 | Fabric KeyBinding API | 默认键 `V`，可在控制设置里改 |
| 录音 | `javax.sound.sampled` | 16kHz、16bit、单声道 PCM（whisper/vosk 都吃这个格式） |
| A 档 STT | faster-whisper `small` | 4070S 上中文准又快 |
| B 档 STT | fork STTA（whisper.cpp）优先，备选 Vosk Java | STTA 已验证支持 Fabric 1.21.1 |

## 4. 模块清单（给 agent 的任务分解）

### 4.1 客户端 mod（A/B 共用）
1. `VoiceKeybind`：注册按键，`FabricKeyBinding`，默认 `V`
2. `MicRecorder`：按住开始录（`TargetDataLine`），松开停止，输出 16kHz PCM byte[]
3. `VoiceClient`：WebSocket 连 `ws://localhost:8765`（A 档）；B 档改为调 fork 后的 STT 模块或本地 `VoskRecognizer`
4. `ChatDisplay`：收到转写文字后发本地聊天框 `§7[语音]§r <玩家名>: 内容`；收到 AI 回复后显示 `§e[村民名]§r 回复内容`
5. 超时/断连处理：sidecar 连不上时聊天框提示"语音服务未启动"，不崩游戏

### 4.2 A 档 sidecar（`sidecar/server.py`）
1. WebSocket 服务，收 PCM → 写临时 wav → faster-whisper 转写 → 返回 `{"text": "..."}`
2. 转写后调中央大脑：按 `SPEC.md` 的输入格式，把文字作为该玩家的 chat 事件发过去
3. 依赖：`pip install faster-whisper websockets`，模型首次运行自动下载

### 4.3 B 档 STT（fork STTA 优先）
1. 拉取 STTA 源码（GPL-3.0，Modrinth slug `stta`，Fabric，支持 1.21.1），阅读其 whisper.cpp JNI 转写链路。
2. fork 后加一个钩子：转写结果除了显示，还按第 5 节格式发往中央大脑。
3. 备选（STTA 不可 fork 时）：`VoskEngine` 自研内嵌 Vosk Java，启动时检查 `config/voicemod/models/`，没有就下载解压（显示进度条或聊天框提示）。
2. `Recognizer` 流式识别：边录边出中间结果，松开按键出最终结果（体验比等整个文件好）
3. 准度兜底：置信度过低时聊天框显示"没听清，再说一遍？"而不是乱猜的文字

## 5. 与中央大脑的接口

转写文字不直接显示了事，必须进入决策流：
- 格式：`{"type":"voice_chat","player":"<玩家名>","text":"<转写内容>","tick":<当前tick>}`
- 注入点：`SPEC.md` 中央计划器的输入收集阶段，和普通聊天事件同等处理
- 距离过滤：只发给玩家 10 格内的"醒着"生物（复用空间裁剪），别让全村的牛都听见

## 6. config 示例（`config/voicemod.json`）

```json
{
  "mode": "sidecar",
  "sidecar_url": "ws://localhost:8765",
  "push_to_talk_key": "V",
  "sample_rate": 16000,
  "brain_url": "http://localhost:11434",
  "brain_model": "qwen2.5:7b",
  "display": { "show_transcript": true, "head_bubble": false }
}
```
`mode`: `sidecar`（A 档）/ `vosk`（B 档），B 档实现完再加。

## 7. 验收标准

1. 进单人存档（开 LAN）或本地服，按住 V 说话，松开 3 秒内聊天框出现 `§7[语音]§r` 转写行，内容基本准确（安静环境下常用词识别率可接受）。
2. 转写文字能触发附近村民 AI 回复（接中央大脑后验证；大脑没好之前先只验证转写显示）。
3. sidecar 没启动时按 V，聊天框提示服务未启动，游戏不崩。
4. B 档：删掉 Python sidecar，只留 mod + Ollama，全流程照样跑通。

## 8. 明确不做的

- TTS 语音播报（本轮砍掉，后续加 Piper sidecar 或系统语音二选一）。
- 服务端 mod（先只做客户端，服务端逻辑复用生态大脑）。
- 降噪/回声消除（先不做，要求玩家用耳机）。
- 多人语音频道（不在范围内）。

## 9. 给 agent 的执行顺序

1. 先做 A 档：mod 录音 + 按键 + WebSocket + sidecar 转写 + 聊天框显示，对照第 7 节第 1、3 条验收。
2. A 档跑通后，按第 5 节把转写接入中央大脑（或留好接口等大脑 ready）。
3. 再做 B 档（fork STTA 优先，备选 Vosk 内嵌），对照第 7 节第 4 条验收。
4. 写 README：Fabric 安装 → mod jar 放 `mods/` → A 档装 Python 依赖 → 按键说明。B 档额外说明模型自动下载。
5. Java 21、Fabric Loader/Yarn 版本对 1.21.1，`fabric.mod.json` 别写错。
