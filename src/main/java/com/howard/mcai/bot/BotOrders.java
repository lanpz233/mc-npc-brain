package com.howard.mcai.bot;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * 派活命令（插件确定性解析，不经过模型 —— 与全项目分工铁律一致）。
 * 玩家在对话里说出这些话，假人立刻照办并回一句话；没命中就返回 false，交给模型聊天。
 */
public final class BotOrders {

    private BotOrders() {}

    public static boolean tryOrder(BotService svc, BotAgent a, Player from, String raw) {
        String msg = raw == null ? "" : raw.trim();
        if (msg.isEmpty()) return false;
        BotState s = a.state;

        // 停下必须排在所有命令最前面：「别跟着我」这类话里带着"跟着我"，
        // 先判停才不会被跟随组抢走（v0.7.0 实测：玩家说"别跟了"停不下来，就是栽在这儿）
        if (isStopOrder(msg)) {
            boolean wasFollowing = a.mode == BotAgent.Mode.FOLLOW;
            a.haltAll(svc);
            svc.sayTo(a, from, wasFollowing ? "好，我不跟了，就在这儿待着。" : "好，我停下了。");
            return true;
        }
        if (has(msg, "跟我来", "跟着我", "跟上", "随我来", "跟紧我", "过来跟我")) {
            a.haltAll(svc);
            a.mode = BotAgent.Mode.FOLLOW;
            a.followUuid = from.getUniqueId();
            svc.sayTo(a, from, "好，我跟着你。");
            return true;
        }
        if (has(msg, "回家", "回据点", "回去休息", "回你的家", "回去吧", "回去了")) {
            if (!s.hasBase()) {
                a.haltAll(svc);
                svc.sayTo(a, from, "我还没安家呢，就在这儿待着吧。");
                return true;
            }
            a.haltAll(svc);
            a.mode = BotAgent.Mode.RETURN_BASE; a.phase = 0;
            a.handle = a.driver.goTo(a.player, BotBrain.baseLoc(svc, s), 1.5);
            svc.sayTo(a, from, "好，我这就回去。");
            return true;
        }
        if (has(msg, "这就是你的家", "把这儿当家", "在这里安家", "这是你的据点")) {
            Location l = a.player.getLocation();
            s.setBase(l.getWorld().getName(), l.getX(), l.getY(), l.getZ());
            s.addFact("在" + from.getName() + "指定的地方安了家");
            svc.storeDirty();
            svc.sayTo(a, from, "好，以后这儿就是我家了。");
            return true;
        }
        if (has(msg, "箱子在这", "这是你的箱子", "记住这个箱子")) {
            // 用他正对着的箱子（3 格内最近的箱子方块）
            org.bukkit.block.Block chest = BotJobs.nearestBlock(a.player.getLocation(), 3, Material.CHEST);
            if (chest == null) { svc.sayTo(a, from, "我没看见箱子啊，你站我边上对着箱子再说。"); return true; }
            s.setChest(chest.getWorld().getName(), chest.getX(), chest.getY(), chest.getZ());
            if (!s.hasBase()) {
                Location l = a.player.getLocation();
                s.setBase(l.getWorld().getName(), l.getX(), l.getY(), l.getZ());
            }
            svc.storeDirty();
            svc.sayTo(a, from, "记住这个箱子了，我的东西往这儿放。");
            return true;
        }
        if (has(msg, "存东西", "存箱子", "放箱子", "把东西放下", "存一下")) {
            if (!s.hasChest) { svc.sayTo(a, from, "我还没有箱子呢，先给我指个箱子吧。"); return true; }
            a.haltAll(svc);
            a.mode = BotAgent.Mode.RETURN_BASE; a.phase = 1;
            a.handle = a.driver.goTo(a.player, BotBrain.chestStand(svc, s), 1.6);
            svc.sayTo(a, from, "好，我去把东西存箱子里。");
            return true;
        }
        if (has(msg, "去钓鱼", "钓鱼去", "钓会鱼", "钓会儿鱼")) {
            startWork(svc, a, from, BotAgent.Job.FISH, "好，我去钓鱼。");
            a.phase = 0;
            return true;
        }
        if (has(msg, "砍树", "砍木头", "伐木", "砍点木头")) {
            startWork(svc, a, from, BotAgent.Job.CHOP, "好，我去砍树。");
            a.phaseUntil = 0;
            return true;
        }
        if (has(msg, "敲石头", "挖石头", "采石", "敲点石头")) {
            startWork(svc, a, from, BotAgent.Job.BREAK, "好，我去敲石头。");
            a.phaseUntil = 0;
            return true;
        }
        if (has(msg, "去挖矿", "挖矿去", "下矿", "挖矿")) {
            if (BotJobs.countItem(a.player, Material.WOODEN_PICKAXE) == 0
                    && countAnyPickaxe(a) == 0) {
                svc.sayTo(a, from, "我连把镐子都没有，挖不了矿啊。");
                return true;
            }
            BotJobs.equipForBlock(a.player, Material.STONE);
            startWork(svc, a, from, BotAgent.Job.MINE, "好，我往前面挖一条矿道。");
            BotJobs.mineSetup(a);
            return true;
        }
        if (has(msg, "守夜", "守着", "站岗", "看着点", "打怪", "巡逻")) {
            a.haltAll(svc);
            a.mode = BotAgent.Mode.WORK;
            a.job = BotAgent.Job.GUARD;
            a.target = a.player.getLocation().clone();
            svc.sayTo(a, from, "好，我守在这儿，有怪我来打。");
            return true;
        }
        if (has(msg, "盖房", "建房", "盖个房", "建个房", "盖间房")) {
            String shortage = BotJobs.buildShortage(a.player);
            if (shortage != null) {
                svc.sayTo(a, from, "盖房的料还不够：" + shortage + "你先给我备齐了再说。");
                return true;
            }
            Location site = BotJobs.findBuildSite(a.player.getLocation());
            if (site == null) { svc.sayTo(a, from, "这附近没块平整地方，盖不了。换个地方吧。"); return true; }
            a.haltAll(svc);
            a.mode = BotAgent.Mode.BUILD;
            a.buildOrigin = site;
            a.buildIndex = 0;
            a.nextActAt = 0;
            // 站到工地南边看着
            a.player.teleport(site.clone().add(2.5, 1, -1.5));
            svc.sayTo(a, from, "好，我在这儿盖间小屋，你等着看。");
            return true;
        }
        if (has(msg, "睡觉", "去休息", "休息吧", "睡吧")) {
            a.haltAll(svc);
            a.mode = BotAgent.Mode.REST;
            svc.sayTo(a, from, "好，我歇着了。");
            return true;
        }
        if (has(msg, "跟我走", "走吧", "出发")) {
            a.haltAll(svc);
            a.mode = BotAgent.Mode.FOLLOW;
            a.followUuid = from.getUniqueId();
            svc.sayTo(a, from, "好，走。");
            return true;
        }
        return false;
    }

    private static void startWork(BotService svc, BotAgent a, Player from, BotAgent.Job job, String line) {
        a.haltAll(svc);
        a.mode = BotAgent.Mode.WORK;
        a.job = job;
        a.phase = 0;
        svc.sayTo(a, from, line);
    }

    private static int countAnyPickaxe(BotAgent a) {
        int n = 0;
        for (org.bukkit.inventory.ItemStack it : a.player.getInventory().getContents())
            if (it != null && it.getType().name().endsWith("_PICKAXE")) n += it.getAmount();
        return n;
    }

    private static boolean has(String msg, String... keys) {
        for (String k : keys) if (msg.contains(k)) return true;
        return false;
    }

    /**
     * "让他停下来"的说法合集。真人说话的花样比词表多，所以分两层：
     * 明确短语 + 短句兜底（整句很短、且是"停/别…"的语气就当停下，
     * 避免把"我停下来歇会儿"这种长句误判——长句多半是闲聊不是命令）。
     */
    static boolean isStopOrder(String msg) {
        if (has(msg, "停下", "别动", "站住", "回来待着", "不用了", "取消",
                "别跟了", "别跟着", "别跟我", "不用跟", "别过来", "别跟上来",
                "原地待着", "就在这待着", "待着别动", "别走了", "不用你了", "解散")) {
            return true;
        }
        String t = msg.replaceAll("[\\s，。！？!?,.、~…]+", "");
        if (t.length() <= 5 && (t.startsWith("停") || t.equals("别跟") || t.equals("别动了")
                || t.equals("好了") || t.equals("够了"))) {
            return true;
        }
        return false;
    }
}
