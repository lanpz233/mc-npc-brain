package com.howard.mcai.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.howard.mcai.McaiPlugin;
import com.howard.mcai.ai.PromptBuilder;
import com.howard.mcai.mob.VillagerState;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 基于 Bukkit/Paper 的状态读取实现。
 * <p>
 * 直接复用 VillagerState 与 PromptBuilder 的既有数据，不重新发明状态存储。
 * 所有方法强制主线程，避免插件在异步回调里访问世界导致 Paper 报错。
 */
public final class BukkitGameStateProvider implements GameStateProvider {

    private static final double MAX_RADIUS = 32.0;
    private static final int MAX_LIMIT = 64;
    /**
     * 方块扫描半径上限。
     * <p>
     * 实体查询走世界空间索引，32 格很便宜；方块只能逐个 getBlockAt（半径 32 的立方体约 27 万次），
     * 在主线程做会明显掉 TPS，所以单独收紧到 16 格（约 3.6 万次）。
     */
    private static final double MAX_BLOCK_RADIUS = 16.0;

    private final McaiPlugin plugin;

    public BukkitGameStateProvider(McaiPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean npcExists(UUID npcId) {
        if (npcId == null) return false;
        VillagerState state = plugin.getVillagers().get(npcId);
        if (state == null) return false;
        Villager villager = state.entity();
        return villager != null && villager.isValid() && !villager.isDead();
    }

    @Override
    public JsonObject npcStatus(UUID npcId, UUID playerUuid) {
        requireMainThread();
        VillagerState state = plugin.getVillagers().get(npcId);
        if (state == null || !npcExists(npcId)) return null;

        Villager villager = state.entity();
        Location location = villager.getLocation();

        JsonObject json = new JsonObject();
        json.addProperty("npc_id", npcId.toString());
        json.addProperty("name", displayName(villager));
        json.addProperty("profession", state.profession().name());
        json.addProperty("profession_zh", state.profession().zhName);
        json.addProperty("health", round(villager.getHealth(), 2));
        json.addProperty("max_health", round(villager.getMaxHealth(), 2));
        json.addProperty("health_pct", round(
                villager.getMaxHealth() <= 0 ? 0 : villager.getHealth() * 100.0 / villager.getMaxHealth(), 1));
        json.addProperty("openness", state.openness());
        json.addProperty("llm_busy", state.llmBusy());

        JsonObject pos = new JsonObject();
        pos.addProperty("world", location.getWorld() == null ? "" : location.getWorld().getName());
        pos.addProperty("x", round(location.getX(), 2));
        pos.addProperty("y", round(location.getY(), 2));
        pos.addProperty("z", round(location.getZ(), 2));
        json.add("location", pos);

        UUID conversationPlayer = state.interactingPlayer();
        if (conversationPlayer == null) {
            json.add("conversation_player", com.google.gson.JsonNull.INSTANCE);
        } else {
            JsonObject player = new JsonObject();
            player.addProperty("uuid", conversationPlayer.toString());
            Player online = Bukkit.getPlayer(conversationPlayer);
            player.addProperty("name", online == null ? "" : online.getName());
            json.add("conversation_player", player);
        }

        JsonObject emotion = new JsonObject();
        emotion.addProperty("fear", state.emotion().fear());
        emotion.addProperty("favor", round(state.emotion().favor(playerUuid), 1));
        json.add("emotion", emotion);

        JsonObject needs = new JsonObject();
        for (Map.Entry<String, Double> entry : state.needs().snapshot().entrySet()) {
            needs.addProperty(entry.getKey(), round(entry.getValue(), 1));
        }
        json.add("needs", needs);

        NpcTask activeTask = plugin.getNpcTaskManager() == null
                ? null : plugin.getNpcTaskManager().activeFor(npcId);
        json.add("active_task_id", activeTask == null
                ? com.google.gson.JsonNull.INSTANCE
                : new com.google.gson.JsonPrimitive(activeTask.id()));
        return json;
    }

    @Override
    public JsonObject npcInventory(UUID npcId) {
        requireMainThread();
        VillagerState state = plugin.getVillagers().get(npcId);
        if (state == null || !npcExists(npcId)) return null;

        JsonObject json = new JsonObject();
        json.addProperty("npc_id", npcId.toString());
        json.addProperty("name", displayName(state.entity()));

        JsonArray items = new JsonArray();
        int totalItems = 0;
        for (Map.Entry<String, Integer> entry : state.inventorySnapshot().entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0) continue;
            Material material = Material.matchMaterial(entry.getKey());
            JsonObject item = new JsonObject();
            item.addProperty("material", entry.getKey());
            item.addProperty("name_zh", material == null
                    ? entry.getKey() : PromptBuilder.materialToZh(material));
            item.addProperty("count", entry.getValue());
            items.add(item);
            totalItems += entry.getValue();
        }
        json.add("items", items);
        json.addProperty("total_items", totalItems);
        json.addProperty("total_types", items.size());

        JsonObject prices = new JsonObject();
        for (Map.Entry<String, Integer> entry : state.dynamicPrices().entrySet()) {
            prices.addProperty(entry.getKey(), entry.getValue());
        }
        json.add("prices", prices);

        JsonArray offered = new JsonArray();
        for (Map.Entry<String, VillagerState.OfferedItem> entry : state.offeredItems().entrySet()) {
            VillagerState.OfferedItem value = entry.getValue();
            Material material = Material.matchMaterial(entry.getKey());
            JsonObject item = new JsonObject();
            item.addProperty("material", entry.getKey());
            item.addProperty("name_zh", material == null
                    ? entry.getKey() : PromptBuilder.materialToZh(material));
            item.addProperty("price", value.price());
            item.addProperty("count", value.count());
            item.add("enchant", value.enchant() == null
                    ? com.google.gson.JsonNull.INSTANCE
                    : new com.google.gson.JsonPrimitive(value.enchant()));
            offered.add(item);
        }
        json.add("offered", offered);
        return json;
    }

    @Override
    public JsonArray nearbyEntities(UUID npcId, double radius, int limit) {
        requireMainThread();
        VillagerState state = plugin.getVillagers().get(npcId);
        if (state == null || !npcExists(npcId)) return new JsonArray();

        Villager self = state.entity();
        Location origin = self.getLocation();
        World world = origin.getWorld();
        if (world == null) return new JsonArray();

        double safeRadius = Math.max(0.5, Math.min(radius, MAX_RADIUS));
        int safeLimit = Math.max(1, Math.min(limit, MAX_LIMIT));

        List<EntityDistance> found = new ArrayList<>();
        for (Entity entity : world.getNearbyEntities(origin, safeRadius, safeRadius, safeRadius)) {
            if (entity.getUniqueId().equals(npcId)) continue;
            if (!entity.isValid() || entity.isDead()) continue;
            found.add(new EntityDistance(entity, entity.getLocation().distance(origin)));
        }
        found.sort(Comparator.comparingDouble(EntityDistance::distance));

        JsonArray array = new JsonArray();
        for (EntityDistance entry : found) {
            if (array.size() >= safeLimit) break;
            array.add(describeEntity(entry.entity(), entry.distance()));
        }
        return array;
    }

    @Override
    public JsonObject nearbyBlocks(UUID npcId, double radius, int limit) {
        requireMainThread();
        VillagerState state = plugin.getVillagers().get(npcId);
        if (state == null || !npcExists(npcId)) return null;

        Location origin = state.entity().getLocation();
        World world = origin.getWorld();
        if (world == null) return null;

        double safeRadius = Math.max(1.0, Math.min(radius, MAX_BLOCK_RADIUS));
        int safeLimit = Math.max(1, Math.min(limit, MAX_LIMIT));

        int r = (int) Math.ceil(safeRadius);
        int ox = origin.getBlockX();
        int oy = origin.getBlockY();
        int oz = origin.getBlockZ();
        int minY = Math.max(world.getMinHeight(), oy - r);
        int maxY = Math.min(world.getMaxHeight() - 1, oy + r);
        double radiusSq = safeRadius * safeRadius;

        Map<Material, Integer> counts = new HashMap<>();
        int scanned = 0;
        // 只扫描水平圆形范围内的方块；角落剪掉能省下约 21% 的 getBlockAt 调用
        for (int x = ox - r; x <= ox + r; x++) {
            double dx = x + 0.5 - origin.getX();
            for (int z = oz - r; z <= oz + r; z++) {
                double dz = z + 0.5 - origin.getZ();
                if (dx * dx + dz * dz > radiusSq) continue;
                for (int y = minY; y <= maxY; y++) {
                    Block block = world.getBlockAt(x, y, z);
                    Material type = block.getType();
                    if (type.isAir()) continue;
                    counts.merge(type, 1, Integer::sum);
                    scanned++;
                }
            }
        }

        List<Map.Entry<Material, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

        JsonArray blocks = new JsonArray();
        for (Map.Entry<Material, Integer> entry : sorted) {
            if (blocks.size() >= safeLimit) break;
            JsonObject item = new JsonObject();
            item.addProperty("material", entry.getKey().name());
            item.addProperty("name_zh", PromptBuilder.materialToZh(entry.getKey()));
            item.addProperty("count", entry.getValue());
            blocks.add(item);
        }

        JsonObject json = new JsonObject();
        json.addProperty("npc_id", npcId.toString());
        json.addProperty("radius", round(safeRadius, 1));
        json.addProperty("limit", safeLimit);
        json.addProperty("scanned_blocks", scanned);
        json.addProperty("types", blocks.size());
        json.add("blocks", blocks);
        return json;
    }

    @Override
    public JsonObject environment(UUID npcId) {
        requireMainThread();
        VillagerState state = plugin.getVillagers().get(npcId);
        if (state == null || !npcExists(npcId)) return null;

        Location location = state.entity().getLocation();
        World world = location.getWorld();
        if (world == null) return null;

        long time = world.getTime();
        boolean storm = world.hasStorm();
        boolean thunder = world.isThundering();
        // 雪地群系的降水是雪而不是雨：用温度判定，避免把"积雪的地面"和"正在下雪"混为一谈
        boolean snowyBiome = location.getBlock().getTemperature() < 0.15;
        String precipitation = snowyBiome
                ? (storm ? "下雪" : "没有下雪")
                : (storm ? "下雨" : "没有下雨");

        JsonObject json = new JsonObject();
        json.addProperty("npc_id", npcId.toString());
        json.addProperty("world", world.getName());
        json.addProperty("biome", location.getBlock().getBiome().name());
        json.addProperty("time_ticks", time);
        json.addProperty("clock", PromptBuilder.mcClock(time));
        json.addProperty("period", PromptBuilder.mcPeriod(time));
        json.addProperty("period_zh", PromptBuilder.mcPeriodZh(time));
        json.addProperty("is_day", PromptBuilder.mcIsDay(time));
        json.addProperty("weather", thunder ? "thunder" : storm ? "rain" : "clear");
        json.addProperty("raining", storm);
        json.addProperty("thundering", thunder);
        json.addProperty("snowy_biome", snowyBiome);
        json.addProperty("precipitation_zh", precipitation);
        json.addProperty("light_level", location.getBlock().getLightLevel());
        return json;
    }

    private JsonObject describeEntity(Entity entity, double distance) {
        JsonObject json = new JsonObject();
        json.addProperty("entity_id", entity.getUniqueId().toString());
        json.addProperty("type", entity.getType().name());
        json.addProperty("name", displayName(entity));
        json.addProperty("distance", round(distance, 2));
        json.addProperty("kind", kindOf(entity));
        json.addProperty("hostile", entity instanceof Monster);

        // 给行为模型可用的绝对坐标：只说"走到玩家面前"时，模型需要玩家的 x/y/z 才能填 move_to 参数，
        // 否则只能照抄 NPC 自己的位置（走 0 格就"到达"）或凭空编坐标。
        Location loc = entity.getLocation();
        json.addProperty("x", round(loc.getX(), 2));
        json.addProperty("y", round(loc.getY(), 2));
        json.addProperty("z", round(loc.getZ(), 2));

        if (entity instanceof LivingEntity living) {
            json.addProperty("health", round(living.getHealth(), 2));
            json.addProperty("max_health", round(living.getMaxHealth(), 2));
            json.addProperty("health_pct", round(living.getMaxHealth() <= 0 ? 0
                    : living.getHealth() * 100.0 / living.getMaxHealth(), 1));
        }
        if (entity instanceof Villager villager) {
            json.addProperty("profession", String.valueOf(villager.getProfession()));
        }
        return json;
    }

    private String kindOf(Entity entity) {
        if (entity instanceof Player) return "PLAYER";
        if (plugin.getVillagers().containsKey(entity.getUniqueId())) return "NPC";
        if (entity instanceof Monster) return "HOSTILE";
        if (entity instanceof LivingEntity) return "MOB";
        return "OTHER";
    }

    private static String displayName(Entity entity) {
        if (entity instanceof Player player) return player.getName();
        String custom = entity.getCustomName();
        if (custom != null && !custom.isBlank()) return custom.replaceAll("§.", "");
        return entity.getType().name();
    }

    private static double round(double value, int digits) {
        double factor = Math.pow(10, digits);
        return Math.round(value * factor) / factor;
    }

    private static void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("GameStateProvider 必须在 Paper 主线程调用");
        }
    }

    private record EntityDistance(Entity entity, double distance) {
    }
}
