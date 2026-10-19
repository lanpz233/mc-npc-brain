# MC AI 生态 — Paper 插件

让 MC 里所有生物都能交流互动、和现实一样。客户端零成本，服务端全包。

## 结构

- `src/` — Paper 1.21.1 插件源码（Gradle，Java 21）
- `docs/` — SPEC-v2.md（设计文档）、APPENDIX-paper-api.md（Paper API 速查）、SPEC-voice.md（语音 mod，已搁置）
- `data/` — 知识库 JSON（**唯一可信源**，改这里）：
  - `items.json` 物品价值表 / `recipes.json` 合成表（含职业知识域）
  - `professions.json` 职业档案 / `personality_tags.json` 人格标签库
  - `surnames.json` + `given_names.json` 生成式命名 / `npc_names.json` 外号池

> 注意：`src/main/resources/` 里有一份 JSON 副本是构建时打进 jar 的，
> 改动一律先改 `data/`，再同步到 `src/main/resources/`。

## 构建

```bash
./gradlew shadowJar
# 输出 build/libs/MC-AI.jar，扔进服务器 plugins/ 重启即可
```

## 运行要求

- Paper 1.21.1，Java 21
- Ollama（`qwen2.5:7b`，`keep_alive=-1`，`OLLAMA_NUM_PARALLEL=4`），见 SPEC §5.1 / §7.1
