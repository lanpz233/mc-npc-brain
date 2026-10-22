package com.howard.mcai.nav;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Openable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自研寻路的 Bukkit 侧：把真实世界读成 {@link NavGrid} 快照、跑 A*、产出路点。
 * <p>
 * 快照分类（每格 = 村民“脚站的位置”）：脚下有支撑、脚位与头位可穿过 → WALK；
 * 关着的木门/木栅栏门 → DOOR（能过，要开门）；水 → WATER；梯子/藤蔓 → CLIMB；
 * 岩浆/火/仙人掌/岩浆块等危险 → 无节点。铁门村民开不了，按墙处理。
 * <p>
 * 读世界只能在主线程；单次规划的方块读取量靠“走廊盒 + 每 NPC 快照缓存 3.5 秒”
 * 控制（跟随每 2 秒重规划一次时复用同一份快照）。规划本身（A*）是纯计算，
 * 数据量小，直接在主线程跑（展开上限 9000 个节点，毫秒级）。
 */
public final class NavService {

    /** 寻路结果：路点（世界坐标，脚位）+ 沿途要开的门格 + 是否真到了终点 */
    public record Plan(List<Location> waypoints, List<int[]> doorCells, boolean reached) {}

    private record CachedGrid(NavGrid grid, long atMs, String worldName) {}

    private final Map<UUID, CachedGrid> cache = new ConcurrentHashMap<>();
    private static final long CACHE_MS = 3_500L;

    /**
     * 规划一条路。from/to 先就近吸附到可站格；完全无路返回 null；
     * 终点被封死时返回走向终点的部分路径（reached=false），调用方走完再重规划。
     */
    public Plan plan(UUID npcId, Location from, Location to) {
        if (from == null || to == null || from.getWorld() == null
                || !from.getWorld().equals(to.getWorld())) return null;
        World world = from.getWorld();
        NavGrid grid = gridFor(npcId, world, from, to);
        if (grid == null) return null;
        int[] s = snap(grid, from.getBlockX(), from.getBlockY(), from.getBlockZ());
        int[] t = snap(grid, to.getBlockX(), to.getBlockY(), to.getBlockZ());
        if (s == null || t == null) return null;
        NavGrid.Result r = grid.findPath(s[0], s[1], s[2], t[0], t[1], t[2]);
        if (r.empty()) return null;
        List<int[]> cells = grid.smooth(r.cells());
        List<Location> wps = new ArrayList<>();
        List<int[]> doors = new ArrayList<>();
        for (int i = 0; i < cells.size(); i++) {
            int[] c = cells.get(i);
            // 起点格不用作路点（人已经站在那）
            if (i > 0) wps.add(new Location(world, c[0] + 0.5, c[1], c[2] + 0.5));
            if (grid.at(c[0], c[1], c[2]) == NavGrid.DOOR) doors.add(c);
        }
        if (wps.isEmpty()) {
            // 只剩起点 = 人已经在终点格里
            wps.add(new Location(world, to.getX(), to.getY(), to.getZ()));
        }
        return new Plan(wps, doors, r.reached());
    }

    /** 取（或重建）覆盖 from/to 的快照；缓存未过期且新起终点都在盒内就复用 */
    private NavGrid gridFor(UUID npcId, World world, Location from, Location to) {
        long now = System.currentTimeMillis();
        CachedGrid c = npcId != null ? cache.get(npcId) : null;
        if (c != null && now - c.atMs() <= CACHE_MS && c.worldName().equals(world.getName())
                && c.grid().inside(from.getBlockX(), from.getBlockY(), from.getBlockZ())
                && c.grid().inside(to.getBlockX(), to.getBlockY(), to.getBlockZ())) {
            return c.grid();
        }
        NavGrid grid = snapshot(world, from, to);
        if (npcId != null && grid != null) cache.put(npcId, new CachedGrid(grid, now, world.getName()));
        return grid;
    }

    /** 走廊盒快照：包住起终点 + 四周留白，尺寸封顶防读爆 */
    private NavGrid snapshot(World world, Location from, Location to) {
        int pad = 6;
        int minX = Math.min(from.getBlockX(), to.getBlockX()) - pad;
        int maxX = Math.max(from.getBlockX(), to.getBlockX()) + pad;
        int minZ = Math.min(from.getBlockZ(), to.getBlockZ()) - pad;
        int maxZ = Math.max(from.getBlockZ(), to.getBlockZ()) + pad;
        int minY = Math.max(world.getMinHeight(), Math.min(from.getBlockY(), to.getBlockY()) - 7);
        int maxY = Math.min(world.getMaxHeight() - 2, Math.max(from.getBlockY(), to.getBlockY()) + 8);
        // 封顶：水平 84、垂直 26；超了以中点裁剪
        if (maxX - minX + 1 > 84) {
            int mid = (minX + maxX) / 2;
            minX = mid - 42;
            maxX = mid + 41;
        }
        if (maxZ - minZ + 1 > 84) {
            int mid = (minZ + maxZ) / 2;
            minZ = mid - 42;
            maxZ = mid + 41;
        }
        if (maxY - minY + 1 > 26) {
            int mid = (minY + maxY) / 2;
            minY = mid - 13;
            maxY = mid + 12;
        }
        int sx = maxX - minX + 1, sy = maxY - minY + 1, sz = maxZ - minZ + 1;
        NavGrid grid = new NavGrid(minX, minY, minZ, sx, sy, sz);
        // 逐列读：整列方块先读进数组（比逐格三次 getBlockAt 省得多），再逐格分类
        Material[] col = new Material[sy + 2];
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int i = 0; i < col.length; i++) {
                    col[i] = world.getBlockAt(x, minY - 1 + i, z).getType();
                }
                for (int y = minY; y <= maxY; y++) {
                    Material below = col[y - minY];
                    Material feet = col[y - minY + 1];
                    Material head = col[y - minY + 2];
                    byte code = classify(world, x, y, z, below, feet, head);
                    if (code != NavGrid.NONE) grid.set(x, y, z, code);
                }
            }
        }
        return grid;
    }

    /** 单格分类：below/feet/head 三块的材质（+门的状态）决定这格能不能站/怎么过 */
    private byte classify(World world, int x, int y, int z, Material below, Material feet, Material head) {
        // 危险一票否决（脚下是岩浆/岩浆块也不行——站上去就烧）
        if (isDanger(feet) || isDanger(head) || below == Material.LAVA
                || below == Material.MAGMA_BLOCK || below == Material.CACTUS
                || below == Material.FIRE || below == Material.SOUL_FIRE) {
            return NavGrid.NONE;
        }
        if (feet == Material.WATER || feet == Material.BUBBLE_COLUMN) return NavGrid.WATER;
        if (isClimb(feet)) return NavGrid.CLIMB;
        // 梯子顶的过渡格：脚下是梯子、身上是空的，可以站出来
        if (isClimb(below) && passable(world, x, y, z, feet) && passable(world, x, y + 1, z, head)) {
            return NavGrid.CLIMB;
        }
        // 关着的木门/木栅栏门占着脚位或头位，且脚下有支撑 → 能过（开门）
        if ((isClosedWoodenDoor(world, x, y, z, feet) || isClosedWoodenDoor(world, x, y + 1, z, head))
                && support(below)) {
            return NavGrid.DOOR;
        }
        if (passable(world, x, y, z, feet) && passable(world, x, y + 1, z, head) && support(below)) {
            return NavGrid.WALK;
        }
        // 薄地毯/雪层垫在脚下：支撑看再下一格
        if ((isThinFloor(feet)) && passable(world, x, y + 1, z, head)) {
            Material under = world.getBlockAt(x, y - 1, z).getType();
            if (support(under)) return NavGrid.WALK;
        }
        return NavGrid.NONE;
    }

    private static boolean isDanger(Material m) {
        return m == Material.LAVA || m == Material.FIRE || m == Material.SOUL_FIRE
                || m == Material.CACTUS || m == Material.POWDER_SNOW
                || m == Material.SWEET_BERRY_BUSH || m == Material.WITHER_ROSE;
    }

    /** 梯子/藤蔓/脚手架？（快照分类与 NavDriver 的攀爬托举共用） */
    public static boolean isClimb(Material m) {
        return m == Material.LADDER || m == Material.VINE || m == Material.WEEPING_VINES
                || m == Material.WEEPING_VINES_PLANT || m == Material.TWISTING_VINES
                || m == Material.TWISTING_VINES_PLANT || m == Material.SCAFFOLDING;
    }

    private static boolean isThinFloor(Material m) {
        return m == Material.SNOW || m.name().endsWith("_CARPET");
    }

    /** 能不能站得住：实心且不是栅栏/墙/门（那些顶面高度怪异，村民站不稳） */
    private static boolean support(Material below) {
        if (!below.isSolid()) return false;
        String n = below.name();
        if (n.endsWith("_FENCE") || n.endsWith("_WALL")) return false;
        return !isDoorLike(below);
    }

    private static boolean isDoorLike(Material m) {
        String n = m.name();
        return n.endsWith("_FENCE_GATE") || (n.endsWith("_DOOR") && !n.endsWith("_TRAPDOOR"));
    }

    /** 这一格的方块能不能穿过：非实心可以；门/栅栏门看开着没有（铁门开不了=false） */
    private boolean passable(World world, int x, int y, int z, Material m) {
        if (!m.isSolid()) return true;
        if (!isDoorLike(m)) return false;
        Block b = world.getBlockAt(x, y, z);
        return b.getBlockData() instanceof Openable o && o.isOpen();
    }

    /** 关着的木门/木栅栏门？（铁门村民开不了，不算） */
    private boolean isClosedWoodenDoor(World world, int x, int y, int z, Material m) {
        if (!isDoorLike(m)) return false;
        if (m == Material.IRON_DOOR || m == Material.IRON_TRAPDOOR) return false;
        Block b = world.getBlockAt(x, y, z);
        return b.getBlockData() instanceof Openable o && !o.isOpen();
    }

    /** 把一格门打开（木门/木栅栏门）；已开/不是门/铁门都不动。返回是否真开了 */
    public static boolean openDoorAt(World world, int x, int y, int z) {
        Block b = world.getBlockAt(x, y, z);
        Material m = b.getType();
        if (!isDoorLike(m) || m == Material.IRON_DOOR) return false;
        if (b.getBlockData() instanceof Openable o && !o.isOpen()) {
            o.setOpen(true);
            b.setBlockData(o);
            boolean gate = m.name().endsWith("_FENCE_GATE");
            world.playSound(b.getLocation().add(0.5, 0.5, 0.5),
                    gate ? org.bukkit.Sound.BLOCK_FENCE_GATE_OPEN
                            : org.bukkit.Sound.BLOCK_WOODEN_DOOR_OPEN, 0.6f, 1.0f);
            return true;
        }
        return false;
    }

    /** 就近吸附：目标格本身不是节点时，在半径内找最近的节点（先同层、再上下） */
    private int[] snap(NavGrid grid, int x, int y, int z) {
        if (grid.isNode(x, y, z)) return new int[]{x, y, z};
        int[] best = null;
        double bestD = Double.MAX_VALUE;
        for (int r = 1; r <= 3; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    for (int dy = -r; dy <= r; dy++) {
                        if (!grid.isNode(x + dx, y + dy, z + dz)) continue;
                        double d = dx * dx + dy * dy * 4 + dz * dz;
                        if (d < bestD) {
                            bestD = d;
                            best = new int[]{x + dx, y + dy, z + dz};
                        }
                    }
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    /** 插件停用时清缓存 */
    public void clear() {
        cache.clear();
    }
}
