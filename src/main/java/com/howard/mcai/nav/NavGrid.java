package com.howard.mcai.nav;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;

/**
 * 自研寻路的纯计算核心（v0.5.0 服主拍板“走自研”）—— 一块方块网格快照 + A*。
 * <p>
 * 为什么不用原版寻路：原版导航只看得见眼前十几步、不会开门（任务期 AI 冻结后更甚）、
 * 不认梯子、远路规划不出来，村民尽在门口/墙角犯傻。这里把村民周围的方块扫成
 * “脚位格”网格：每格表示“脚站在这里”是否可行（脚下有支撑、头顶有空、不是危险块），
 * 再在格子上跑 A*。本类只碰 byte 数组，离线可测；把 Bukkit 世界读成网格的是 NavService。
 * <p>
 * 格子类型：WALK 普通可站、DOOR 关着的木门/木栅栏门（能过、要开门、代价高）、
 * WATER 可游、CLIMB 梯子/藤蔓（可上下）。移动规则：平走/斜走（禁贴角穿墙）、
 * 上 1 格台阶、下落最多 3 格（不摔伤）、水里可上下游、梯子可上下爬。
 */
public final class NavGrid {

    public static final byte NONE = 0;
    public static final byte WALK = 1;
    public static final byte DOOR = 2;
    public static final byte WATER = 3;
    public static final byte CLIMB = 4;

    /** 单次 A* 最多展开的节点数 —— 兜底防病态地图把主线程/异步线程烧穿 */
    static final int MAX_EXPANSIONS = 9000;

    private final int ox, oy, oz;
    private final int sx, sy, sz;
    private final byte[] cells;

    public NavGrid(int ox, int oy, int oz, int sx, int sy, int sz) {
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        this.cells = new byte[sx * sy * sz];
    }

    public int sizeX() { return sx; }
    public int sizeY() { return sy; }
    public int sizeZ() { return sz; }
    public int originX() { return ox; }
    public int originY() { return oy; }
    public int originZ() { return oz; }

    private int idx(int x, int y, int z) {
        return ((y - oy) * sz + (z - oz)) * sx + (x - ox);
    }

    public boolean inside(int x, int y, int z) {
        return x >= ox && x < ox + sx && y >= oy && y < oy + sy && z >= oz && z < oz + sz;
    }

    public void set(int x, int y, int z, byte code) {
        if (inside(x, y, z)) cells[idx(x, y, z)] = code;
    }

    public byte at(int x, int y, int z) {
        return inside(x, y, z) ? cells[idx(x, y, z)] : NONE;
    }

    public boolean isNode(int x, int y, int z) {
        return at(x, y, z) != NONE;
    }

    /** 寻路结果：cells 是从起点到终点的脚位（含头尾）；reached=false 表示只走到半路最近点 */
    public record Result(List<int[]> cells, boolean reached) {
        public boolean empty() { return cells.isEmpty(); }
    }

    /**
     * A* 寻路。起点/终点都必须是格子内的节点（调用方先就近吸附）。
     * 终点不可达时返回“离终点最近处”的部分路径（reached=false）——滚动重规划
     * 靠它先往前走一段再重算，而不是原地判死。
     */
    public Result findPath(int startX, int startY, int startZ, int goalX, int goalY, int goalZ) {
        if (!isNode(startX, startY, startZ) || !isNode(goalX, goalY, goalZ)) {
            return new Result(List.of(), false);
        }
        int n = cells.length;
        float[] g = new float[n];
        Arrays.fill(g, Float.POSITIVE_INFINITY);
        int[] came = new int[n];
        Arrays.fill(came, -1);
        boolean[] closed = new boolean[n];
        int startIdx = idx(startX, startY, startZ);
        int goalIdx = idx(goalX, goalY, goalZ);
        g[startIdx] = 0f;

        // 优先队列元素：[fScore, idx]
        PriorityQueue<float[]> open = new PriorityQueue<>((a, b) -> Float.compare(a[0], b[0]));
        open.add(new float[]{heuristic(startX, startY, startZ, goalX, goalY, goalZ), startIdx});

        int bestIdx = startIdx;
        float bestH = heuristic(startX, startY, startZ, goalX, goalY, goalZ);
        int expansions = 0;

        while (!open.isEmpty()) {
            float[] cur = open.poll();
            int curIdx = (int) cur[1];
            if (closed[curIdx]) continue;
            closed[curIdx] = true;
            if (curIdx == goalIdx) {
                return new Result(reconstruct(came, curIdx), true);
            }
            if (++expansions > MAX_EXPANSIONS) break;
            int cx = ox + curIdx % sx;
            int cz = oz + (curIdx / sx) % sz;
            int cy = oy + curIdx / (sx * sz);
            float h = heuristic(cx, cy, cz, goalX, goalY, goalZ);
            if (h < bestH) {
                bestH = h;
                bestIdx = curIdx;
            }
            byte curType = cells[curIdx];
            forEachNeighbor(cx, cy, cz, curType, (nx, ny, nz, cost) -> {
                int nIdx = idx(nx, ny, nz);
                if (closed[nIdx]) return;
                float ng = g[curIdx] + cost;
                if (ng < g[nIdx]) {
                    g[nIdx] = ng;
                    came[nIdx] = curIdx;
                    open.add(new float[]{ng + heuristic(nx, ny, nz, goalX, goalY, goalZ), nIdx});
                }
            });
        }
        if (bestIdx != startIdx) {
            return new Result(reconstruct(came, bestIdx), false);
        }
        return new Result(List.of(), false);
    }

    private interface NeighborSink {
        void accept(int x, int y, int z, float cost);
    }

    /** 按移动规则枚举一个节点的全部可达邻居（见类注释） */
    private void forEachNeighbor(int x, int y, int z, byte type, NeighborSink sink) {
        // 梯子/水里的垂直移动
        if (type == CLIMB || type == WATER) {
            byte up = at(x, y + 1, z);
            if (up == type || (type == CLIMB && up == WALK)) {
                sink.accept(x, y + 1, z, type == CLIMB ? 1.6f : 2.2f);
            }
            byte down = at(x, y - 1, z);
            if (down == type || down == WALK || down == DOOR) {
                sink.accept(x, y - 1, z, type == CLIMB ? 1.2f : 1.8f);
            }
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                boolean diag = dx != 0 && dz != 0;
                float base = diag ? 1.45f : 1.0f;
                // 同层
                byte same = at(x + dx, y, z + dz);
                if (same != NONE && (!diag || cornerClear(x, y, z, dx, dz))) {
                    sink.accept(x + dx, y, z + dz, cost(base, type, same));
                }
                // 上台阶 1 格
                byte up = at(x + dx, y + 1, z + dz);
                if (up != NONE && up != WATER && (!diag || cornerClear(x, y + 1, z, dx, dz))) {
                    sink.accept(x + dx, y + 1, z + dz, cost(base + 0.4f, type, up));
                }
                // 下落 1~3 格（取最浅的落点即可，落点必须是节点）
                for (int d = 1; d <= 3; d++) {
                    byte dn = at(x + dx, y - d, z + dz);
                    if (dn != NONE && dn != CLIMB) {
                        if (!diag || cornerClear(x, y - d, z, dx, dz)) {
                            sink.accept(x + dx, y - d, z + dz, cost(base + 0.2f * d, type, dn));
                        }
                        break;
                    }
                }
            }
        }
    }

    /** 斜向移动不许贴着墙角穿过去：两个正交邻居在该层都得是可通行的（门不行） */
    private boolean cornerClear(int x, int y, int z, int dx, int dz) {
        byte a = at(x + dx, y, z);
        byte b = at(x, y, z + dz);
        return a != NONE && a != DOOR && b != NONE && b != DOOR;
    }

    /** 一步的代价：进门要开门（贵）、下水游泳慢得多（按步长放大）、爬梯子略贵 */
    private static float cost(float base, byte from, byte to) {
        if (to == WATER) return base * 2.6f + 0.5f;
        float c = base;
        if (to == DOOR) c += 2.5f;
        if (to == CLIMB) c += 0.6f;
        if (from == WATER && to != WATER) c *= 1.3f;   // 爬上岸也费劲
        return c;
    }

    private static float heuristic(int x, int y, int z, int gx, int gy, int gz) {
        int dx = Math.abs(x - gx);
        int dz = Math.abs(z - gz);
        int dy = Math.abs(y - gy);
        float octile = Math.max(dx, dz) + 0.45f * Math.min(dx, dz);
        return octile + dy * 1.0f;
    }

    private List<int[]> reconstruct(int[] came, int endIdx) {
        List<int[]> path = new ArrayList<>();
        int cur = endIdx;
        while (cur != -1) {
            int x = ox + cur % sx;
            int z = oz + (cur / sx) % sz;
            int y = oy + cur / (sx * sz);
            path.add(new int[]{x, y, z});
            cur = came[cur];
        }
        java.util.Collections.reverse(path);
        return path;
    }

    /**
     * 路径平滑：贪心地把能直视的中间点删掉，走位不贴格子线。
     * 直视判定沿线采样：采样点必须有节点且与插值高度差 ≤1；途中穿过门格则不许拉直
     * （门必须作为路点保留，开门动作靠它触发）。
     */
    public List<int[]> smooth(List<int[]> path) {
        if (path.size() <= 2) return path;
        List<int[]> out = new ArrayList<>();
        out.add(path.get(0));
        int i = 0;
        while (i < path.size() - 1) {
            int far = i + 1;
            for (int j = path.size() - 1; j > i + 1; j--) {
                if (lineClear(path.get(i), path.get(j))) {
                    far = j;
                    break;
                }
            }
            out.add(path.get(far));
            i = far;
        }
        return out;
    }

    private boolean lineClear(int[] a, int[] b) {
        double dx = b[0] - a[0];
        double dy = b[1] - a[1];
        double dz = b[2] - a[2];
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (Math.abs(dy) > 1) return false;   // 有落差的段不拉直，保留台阶/落点
        int steps = Math.max(1, (int) Math.ceil(dist / 0.4));
        for (int s = 1; s < steps; s++) {
            double t = (double) s / steps;
            int x = (int) Math.round(a[0] + dx * t);
            int y = (int) Math.round(a[1] + dy * t);
            int z = (int) Math.round(a[2] + dz * t);
            byte c = at(x, y, z);
            if (c == NONE) {
                // 高度差 1 以内时，采样点上下一层有节点也算能走（台阶面）
                if (at(x, y - 1, z) == NONE && at(x, y + 1, z) == NONE) return false;
                if (at(x, y - 1, z) == DOOR || at(x, y + 1, z) == DOOR) return false;
            }
            if (c == DOOR) return false;      // 途中过门：门是必经路点
        }
        return true;
    }
}
