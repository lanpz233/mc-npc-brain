package com.howard.mcai.nav;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自研寻路 A* 的离线回归：字符地图搭场景 —— '.'=可站 '#'=实心(无节点) 'D'=门
 * '~'=水 'H'=梯子。layers[y][z] 是一行 x。
 */
class NavGridTest {

    private static NavGrid grid(String[]... layers) {
        int sy = layers.length;
        int sz = layers[0].length;
        int sx = layers[0][0].length();
        NavGrid g = new NavGrid(0, 0, 0, sx, sy, sz);
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                String row = layers[y][z];
                for (int x = 0; x < row.length(); x++) {
                    byte code = switch (row.charAt(x)) {
                        case '.' -> NavGrid.WALK;
                        case 'D' -> NavGrid.DOOR;
                        case '~' -> NavGrid.WATER;
                        case 'H' -> NavGrid.CLIMB;
                        default -> NavGrid.NONE;
                    };
                    if (code != NavGrid.NONE) g.set(x, y, z, code);
                }
            }
        }
        return g;
    }

    @Test
    void straightLineAndSmooth() {
        NavGrid g = grid(new String[]{"....."});
        NavGrid.Result r = g.findPath(0, 0, 0, 4, 0, 0);
        assertTrue(r.reached());
        assertEquals(5, r.cells().size());
        List<int[]> sm = g.smooth(r.cells());
        assertEquals(2, sm.size(), "笔直的空地拉直后只剩头尾两个点");
    }

    @Test
    void aroundWallThroughGap() {
        NavGrid g = grid(new String[]{
                ".....",
                ".###.",
                "...#.",
                ".###.",
                ".....",
        });
        NavGrid.Result r = g.findPath(0, 0, 2, 4, 0, 2);
        assertTrue(r.reached(), "有缺口就该绕得过去");
    }

    @Test
    void throughDoorKeepsDoorWaypoint() {
        NavGrid g = grid(new String[]{
                "..#..",
                "..D..",
                "..#..",
        });
        NavGrid.Result r = g.findPath(0, 0, 1, 4, 0, 1);
        assertTrue(r.reached());
        boolean viaDoor = r.cells().stream().anyMatch(c -> c[0] == 2 && c[2] == 1);
        assertTrue(viaDoor, "唯一的通道是门，必须从门格走");
        List<int[]> sm = g.smooth(r.cells());
        boolean doorKept = sm.stream().anyMatch(c -> c[0] == 2 && c[2] == 1);
        assertTrue(doorKept, "平滑不许把门路点拉掉——开门动作靠它触发");
    }

    @Test
    void stepUpAndDropDown() {
        NavGrid g = grid(
                new String[]{"...##", ".....", "....."},
                new String[]{"###..", "###..", "###.."});
        NavGrid.Result up = g.findPath(0, 0, 0, 4, 1, 0);
        assertTrue(up.reached(), "一格台阶应该上得去");
        NavGrid.Result down = g.findPath(4, 1, 0, 0, 0, 0);
        assertTrue(down.reached(), "一格落差应该下得来");
    }

    @Test
    void ladderConnectsFloors() {
        NavGrid g = grid(
                new String[]{"..H.."},
                new String[]{"##H##"},
                new String[]{"..H.."});
        NavGrid.Result r = g.findPath(0, 0, 0, 4, 2, 0);
        assertTrue(r.reached(), "梯子应该把两层连起来");
    }

    @Test
    void prefersLandBridgeOverWater() {
        NavGrid g = grid(new String[]{
                "..~..",
                "..~..",
                ".....",
        });
        NavGrid.Result r = g.findPath(0, 0, 0, 4, 0, 0);
        assertTrue(r.reached());
        boolean swam = r.cells().stream().anyMatch(c -> g.at(c[0], c[1], c[2]) == NavGrid.WATER);
        assertFalse(swam, "有桥（第三行）就该绕桥，不下水");
    }

    @Test
    void swimsWhenNoBridge() {
        NavGrid g = grid(new String[]{
                "..~..",
                "..~..",
        });
        NavGrid.Result r = g.findPath(0, 0, 0, 4, 0, 0);
        assertTrue(r.reached());
        boolean swam = r.cells().stream().anyMatch(c -> g.at(c[0], c[1], c[2]) == NavGrid.WATER);
        assertTrue(swam, "没桥时该下水游过去");
    }

    @Test
    void noCornerCutting() {
        NavGrid g = grid(new String[]{
                ".#",
                "#.",
        });
        NavGrid.Result r = g.findPath(0, 0, 0, 1, 0, 1);
        assertTrue(r.empty(), "两个墙角夹着的对角线不许硬挤过去");
    }

    @Test
    void enclosedGoalReturnsPartial() {
        NavGrid g = grid(new String[]{
                ".....",
                ".###.",
                ".#.#.",
                ".###.",
                ".....",
        });
        NavGrid.Result r = g.findPath(0, 0, 0, 2, 0, 2);
        assertFalse(r.reached());
        assertFalse(r.empty(), "到不了也要给出往目标方向的部分路径，供滚动重规划");
    }

    @Test
    void fenceIsNotANode() {
        // '#' 在地图里就是无节点（栅栏在快照分类阶段不会生成可站格）——整排封死即到不了；
        // 按滚动重规划的约定会给一段“走到墙根”的部分路径，但绝不许穿墙
        NavGrid g = grid(new String[]{
                "..#..",
                "..#..",
                "..#..",
        });
        NavGrid.Result r = g.findPath(0, 0, 1, 4, 0, 1);
        assertFalse(r.reached());
        assertTrue(r.cells().stream().allMatch(c -> c[0] < 2), "部分路径也只许停在墙这边");
    }
}
