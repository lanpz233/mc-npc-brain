package com.howard.mcai.listener;

import com.howard.mcai.McaiPlugin;
import com.howard.mcai.mob.VillagerProfession;
import com.howard.mcai.mob.VillagerState;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.world.ChunkLoadEvent;

import java.util.Map;
import java.util.UUID;

/**
 * 原版村民自动注册 —— 监听 ChunkLoadEvent，扫描里面所有 Villager entity 自动注册到 AI 管理。
 * <p>
 * 为什么需要这个？
 * 原版世界里的村庄会自然 spawn 村民——农夫、牧师、铁匠、屠夫……
 * 这些村民不是通过 /ai spawn 创建的，
 * 所以不会被自动注册到 villagers map → 不会有 VillagerState → 不会有专属配方。
 * 结果就是：
 * 1. 右键这个村民 → PlayerInteractListener 找不到 VillagerState → 触发不了 LLM
 * 2. 即使触发了（万一绕过了）→ 没 VillagerState 等于没人设 → LLM 胡说八道
 * 3. GUI 永远是原版随机配方
 *
 * 这个 listener 让**世界上所有已加载的 Villager entity** 都自动拥有 AI 能力。
 * 同时配合 onEnable 里的手动扫 chunks，确保 reload 时已经加载的村民也能立刻注册。
 * <p>
 * 另外它还监听 CreatureSpawnEvent，捕获玩家用刷怪蛋 / {@code /summon} 现场放下的村民 ——
 * 那类实体出现在已加载区块中，不会触发 ChunkLoadEvent，否则永远注册不上。
 */
public final class VillagerAutoRegisterListener implements Listener {

    private final McaiPlugin plugin;
    private final Map<UUID, VillagerState> villagers;

    public VillagerAutoRegisterListener(McaiPlugin plugin, Map<UUID, VillagerState> villagers) {
        this.plugin = plugin;
        this.villagers = villagers;
    }

    /** 每次 chunk 加载时扫描里面所有 Villager entity */
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        Entity[] entities = event.getChunk().getEntities();
        for (Entity entity : entities) {
            if (entity instanceof Villager villager) {
                registerIfNew(villager);
            }
        }
    }

    /**
     * 玩家手动放置的村民（刷怪蛋 / {@code /summon}）—— 补上 ChunkLoadEvent 覆盖不到的缺口。
     * <p>
     * 这类村民出现在**已经加载**的区块里，不会触发 ChunkLoadEvent，光靠上面那个监听永远注册不上：
     * 右键没反应、没有专属配方、按 F 打不开背包、GUI 里也查不到状态。
     * <p>
     * 只认 SPAWNER_EGG（刷怪蛋）与 COMMAND（/summon）两种来源 —— 不算 NATURAL/结构生成，
     * 避免世界加载时每个反序列化的实体都触发一次注册（那属于启动扫描的活儿）。
     * 实体的职业此时必定是 {@code none}（还没认领工作站），等它上工后由
     * {@link McaiPlugin#refreshVillagerProfession} 在 60 秒轮询里自动纠正成真实职业。
     */
    @EventHandler(ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof Villager villager)) return;
        CreatureSpawnEvent.SpawnReason reason = event.getSpawnReason();
        if (reason != CreatureSpawnEvent.SpawnReason.SPAWNER_EGG
                && reason != CreatureSpawnEvent.SpawnReason.COMMAND) return;
        // 延后一个 tick 再注册：本事件触发时实体刚被交给世界、还没完全就位
        // （initVillagerShop / 关系配对要读位置），同步注册容易踩到半初始化状态
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (villager.isValid()) registerIfNew(villager);
        });
    }

    /** 把一个原版村民注册进 AI 管理 —— 如果他还没被注册过 */
    private void registerIfNew(Villager villager) {
        UUID id = villager.getUniqueId();
        if (villagers.containsKey(id)) return;  // 已注册 → 跳过

        // v2：傻子/无职业村民也要 AI（用户要求——没职业也能聊，人设更"傻"即可）
        VillagerProfession prof = VillagerProfession.fromMinecraftProfession(villager.getProfession());

        // 名字处理：
        // 1. 原本没名字 → 随机唯一中文名（不再用职业名"铁匠/农夫"，不然全村一个名）
        // 2. 名字=职业名（旧版持久化留下的"牧师/农夫"）→ 也当成无名改掉，
        //    否则 Paper 实体数据里存着"§6牧师"，重启后永远顶着职业名
        String custom = villager.getCustomName() != null ? villager.getCustomName().replaceAll("§.", "") : null;
        String name = (custom == null || custom.equals(prof.zhName))
                ? plugin.uniqueRandomName()
                : custom;
        // 全局唯一兜底：旧版持久化的自定义名也可能撞名（多个"农夫老李"），撞了就换随机名
        name = plugin.ensureUniqueName(name);
        if (custom != null && !custom.equals(name)) {
            plugin.getLogger().info("§a[MC-AI] 旧职业名村民改名: " + custom + " → " + name);
        }
        villager.setCustomName("§6" + name);
        villager.setCustomNameVisible(true);
        villager.setPersistent(true);

        // 注册
        VillagerState state = new VillagerState(villager);
        villagers.put(id, state);
        // 从磁盘恢复该村民的历史记忆（对话/事实/砍价/上架）——
        // 不然运行中途注册的村民永远没有记忆，之前 writeToFile 也会因查不到快照静默丢数据
        plugin.getMemoryStore().loadVillager(state);

        plugin.getLogger().info("§a[MC-AI] 自动注册原版村民: " + name + "(" + prof.zhName + ") 职业=" + villager.getProfession());
        plugin.initVillagerShop(villager, name);

        // 社会关系补配：新村民进村后与现有村民配对夫妻/兄弟姐妹（幂等，已配对的不动），
        // 配完立即标脏落盘，保证重启后新村民的家庭关系不丢；兄弟随姓改名走 uniqueNameWithSurname
        com.howard.mcai.planner.SocialRelationshipGenerator.assignRelationshipsFor(state, villagers, plugin::uniqueNameWithSurname);
        villagers.keySet().forEach(plugin.getMemoryStore()::markDirty);
    }
}
