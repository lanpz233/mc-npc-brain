# 附录 A：Paper 1.21.1 插件开发技术速查

> 给 coding agent 的 API 参考。SPEC 正文只写"做什么"，这份写"用什么调"。
> 官方 Javadoc（把版本号换成 1.21.1 即可）：
> - Mob（含 getPathfinder/lookAt）：https://jd.papermc.io/paper/1.21.11/org/bukkit/entity/Mob.html
> - Pathfinder（含 moveTo/stopPathfinding）：https://jd.papermc.io/paper/1.21.3/com/destroystokyo/paper/entity/Pathfinder.html

## A.1 构建配置

- Java **21**（MC 1.21 强制要求，maven.compiler / toolchain 设 21）
- Paper API 依赖（`compileOnly`，服务端自带）：

```gradle
// Gradle
repositories {
    maven { url = uri("https://repo.papermc.io/repository/maven-public/") }
}
dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
}
```

```xml
<!-- Maven -->
<repository>
    <id>papermc</id>
    <url>https://repo.papermc.io/repository/maven-public/</url>
</repository>
<dependency>
    <groupId>io.papermc.paper</groupId>
    <artifactId>paper-api</artifactId>
    <version>1.21.1-R0.1-SNAPSHOT</version>
    <scope>provided</scope>
</dependency>
```

```yaml
# src/main/resources/plugin.yml
name: NpcBrain
version: 0.1.0
main: com.example.npcbrain.NpcBrainPlugin
api-version: "1.21"
author: Howie
description: AI ecosystem - every creature gets a brain
```

## A.2 线程铁律（P0）

- **Bukkit API 只能在主线程调用**（实体移动、发消息、刷气泡）。
- **LLM HTTP 调用必须异步**（`runTaskAsynchronously`），拿到结果后再切回主线程执行。
- 违反 = 随机崩服 / 卡主线程。这是插件开发第一铁律。

```java
// Tick loop：主线程定时器
Bukkit.getScheduler().runTaskTimer(plugin, () -> {
    planner.tick(); // 收集状态（主线程，读实体）
}, 0L, 60L); // 60 tick = 3 秒

// LLM 调用：异步
Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
    String json = ollama.chat(prompt);          // 阻塞等 LLM
    Bukkit.getScheduler().runTask(plugin, () -> {
        executor.dispatch(json);                // 回主线程执行游戏动作
    });
});
```

## A.3 实体操作速查

```java
// 附近实体（空间裁剪：10 格）
Collection<Entity> near = player.getNearbyEntities(10, 10, 10);

// 寻路移动（对应 BehaviorRegistry 的 move_to / flee_from / follow）
if (entity instanceof Mob mob) {
    mob.getPathfinder().moveTo(targetLocation, 1.0);   // Location + 速度倍率
    mob.getPathfinder().moveTo(targetEntity, 1.2);      // 跟随实体
    mob.getPathfinder().stopPathfinding();              // 停下
}

// 朝向（说话时看向玩家）
if (entity instanceof Mob mob) {
    mob.lookAt(player);   // 也有 lookAt(Location)、lookAt(x, y, z)
}

// 攻击目标（attack）
if (entity instanceof Mob mob && target instanceof LivingEntity le) {
    mob.setTarget(le);
}
```

## A.4 头顶气泡（TextDisplay，见 SPEC 2.4）

```java
// 生成气泡并挂到生物身上当乘客
TextDisplay bubble = world.spawn(entity.getLocation().add(0, 2.2, 0), TextDisplay.class);
bubble.setText(Component.text("你好啊！"));          // Adventure Component
bubble.setBillboard(Display.Billboard.CENTER);      // 永远面向玩家
bubble.setSeeThrough(false);
bubble.setBackgroundColor(Color.fromARGB(128, 0, 0, 0)); // 半透明黑底
entity.addPassenger(bubble);                        // riding，自动跟随

// 5 秒后移除（bubble_duration_sec 可配置）
Bukkit.getScheduler().runTaskLater(plugin, bubble::remove, 100L);
```

## A.5 聊天与事件

```java
// 发消息（聊天框留记录，见 SPEC 2.4）
player.sendMessage(Component.text("§e[铁匠老王]§r 打铁得加钱！"));

// 监听玩家聊天 → 注入中央大脑（见 SPEC 第 5 节 voice_chat 格式）
@EventHandler
public void onChat(AsyncChatEvent event) {
    String text = PlainTextComponentSerializer.plainText().serialize(event.message());
    // 注意：AsyncChatEvent 在异步线程，调 Bukkit API 前先切主线程或只做数据收集
    planner.onPlayerChat(event.getPlayer().getUniqueId(), text);
}
```

## A.6 实体持久化数据（记忆存储，见 SPEC 3.x）

```java
// 每个生物的记忆（favor、notes、大嘴巴指数）存这里，重启不丢
NamespacedKey key = new NamespacedKey(plugin, "favor");
entity.getPersistentDataContainer().set(key, PersistentDataType.INTEGER, 25);
int favor = entity.getPersistentDataContainer().getOrDefault(key, PersistentDataType.INTEGER, 0);
// 复杂结构（notes 列表、players map）→ 序列化成 JSON 字符串存 PersistentDataType.STRING
```

## A.7 调 Ollama（LLM 调用）

- 用 JDK 自带 `java.net.http.HttpClient`，不用引第三方库。
- Endpoint：`POST http://localhost:11434/api/chat`
- Body：`{"model":"qwen2.5-7b-instruct","messages":[...],"stream":false,"format":{...}}`
  - `format` 传 JSON schema = 约束解码（见 SPEC 5.1）
  - 气泡台词用 `"stream":true`，逐 token 更新 TextDisplay（打字机效果）
- 超时设 30 秒；失败 → fallback idle（见 SPEC 2.6）。

## A.8 调试建议

- 本地 Paper 1.21.1 服务端 + 插件 jar 扔 `plugins/`，`/reload` 别用（有坑），改完重启服。
- 先单村民 M1 跑通，再加生物种类。
- 日志分级：`getLogger().info()` 打决策 JSON，方便对着 SPEC 验收。
