package com.howard.mcai.bot;

import java.util.ArrayList;
import java.util.List;

/**
 * 假人的小屋图纸（纯计算，不碰 Bukkit —— 可离线单测）。
 * <p>
 * 5×5 占地、内空 3×3：地板一层、墙高 3、平顶；南墙正中留门（1 宽 2 高）；
 * 屋里西北角放床、旁边放箱子。施工顺序就是 cells() 的顺序：
 * 地板 → 墙（逐层）→ 屋顶 → 门 → 床 → 箱子，执行器按序逐块放即可。
 * 坐标为相对小屋西南角地面的偏移（dx 向东、dy 向上、dz 向南）。
 */
public final class HutBlueprint {

    public enum Part { PLANK, DOOR, BED_FOOT, BED_HEAD, CHEST }

    public record Cell(int dx, int dy, int dz, Part part) {}

    public static final int SIZE = 5;
    public static final int WALL_H = 3;

    private HutBlueprint() {}

    public static List<Cell> cells() {
        List<Cell> out = new ArrayList<>();
        // 地板 dy=0
        for (int x = 0; x < SIZE; x++) for (int z = 0; z < SIZE; z++)
            out.add(new Cell(x, 0, z, Part.PLANK));
        // 墙 dy=1..3：只围边；南墙（z=SIZE-1）正中 x=2 在 dy=1,2 留门洞
        for (int dy = 1; dy <= WALL_H; dy++) {
            for (int x = 0; x < SIZE; x++) for (int z = 0; z < SIZE; z++) {
                boolean edge = x == 0 || z == 0 || x == SIZE - 1 || z == SIZE - 1;
                if (!edge) continue;
                boolean doorGap = z == SIZE - 1 && x == 2 && dy <= 2;
                if (doorGap) continue;
                out.add(new Cell(x, dy, z, Part.PLANK));
            }
        }
        // 屋顶 dy=4 整片
        for (int x = 0; x < SIZE; x++) for (int z = 0; z < SIZE; z++)
            out.add(new Cell(x, WALL_H + 1, z, Part.PLANK));
        // 门（下格，执行器放双格门数据）
        out.add(new Cell(2, 1, SIZE - 1, Part.DOOR));
        // 床：西北角内侧，脚在 (1,1,1)、头朝东 (2,1,1)
        out.add(new Cell(1, 1, 1, Part.BED_FOOT));
        out.add(new Cell(2, 1, 1, Part.BED_HEAD));
        // 箱子：床脚南边 (1,1,2)
        out.add(new Cell(1, 1, 2, Part.CHEST));
        return out;
    }

    /** 木板总需求（地板+墙+顶）。 */
    public static int planksNeeded() {
        int n = 0;
        for (Cell c : cells()) if (c.part() == Part.PLANK) n++;
        return n;
    }

    /** 内空是否干净（图纸自洽检查用）：内部 3×3×3 不应有 PLANK。 */
    public static boolean interiorClear() {
        for (Cell c : cells()) {
            if (c.part() != Part.PLANK) continue;
            boolean inside = c.dx() >= 1 && c.dx() <= 3 && c.dz() >= 1 && c.dz() <= 3
                    && c.dy() >= 1 && c.dy() <= 3;
            if (inside) return false;
        }
        return true;
    }
}
