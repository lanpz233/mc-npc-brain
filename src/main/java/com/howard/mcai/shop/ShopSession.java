package com.howard.mcai.shop;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 一次交易会话 —— 某个玩家在某个村民商店里的进行中状态。
 * <p>
 * 存在的理由（Fong 2026-10-21 定的路线二替代办法）：商店界面开着时不能打字聊天，
 * 玩家可以**关窗去聊天再回来，砍价进度不丢** —— 砍好的价、村民刚说的话、正在看的商品
 * 都记在这里，而不是绑在窗口上。窗口只是这个状态的一张皮。
 * <p>
 * 只存内存（不落盘）：服务器重启后砍价进度清零是可接受的（谈好的价本来就是临时的），
 * 已成交的交易走背包与好感的既有持久化，不依赖会话。
 * 线程：GUI 与聊天都在主线程/会话管理器内同步访问，内部仍用 synchronized 兜底。
 */
public final class ShopSession {

    /** 会话闲置超时：超过这个时长没再打开商店/砍价，进度作废（Manager 清理） */
    public static final long IDLE_TTL_MS = 10 * 60_000L;

    private final UUID npcUuid;
    private final UUID playerUuid;
    private final long createdAtMs;
    private long lastTouchMs;

    /** 砍价谈成的价：offerId → 当前谈成价（绿宝石）。可多轮砍价、只降不升（bargainedOffers 记砍过的商品） */
    private final Map<String, Integer> agreedPrices = new HashMap<>();

    /** 抬价谈成的收购价：offerId → 他肯付的绿宝石（卖货方向，与 agreedPrices 分开记，单调性相反） */
    private final Map<String, Integer> agreedPays = new HashMap<>();
    private final Set<String> bargainedOffers = new HashSet<>();

    /** 村民最近一句要显示在界面台词栏的话（开店时模型的那句台词、还价/成交的系统台词都走这里） */
    private String npcLine;

    /** 玩家当前选中的商品 offerId（点开详情/砍价面板时用）；null = 在商品列表页 */
    private String selectedOfferId;

    /** 砍价被还价后的待确认价：offerId → 村民的还价（底线价）。玩家点「接受」才落定 recordBargain */
    private final Map<String, Integer> pendingCounters = new HashMap<>();

    public ShopSession(UUID npcUuid, UUID playerUuid, long nowMs) {
        this.npcUuid = npcUuid;
        this.playerUuid = playerUuid;
        this.createdAtMs = nowMs;
        this.lastTouchMs = nowMs;
    }

    public UUID npcUuid() {
        return npcUuid;
    }

    public UUID playerUuid() {
        return playerUuid;
    }

    public long createdAtMs() {
        return createdAtMs;
    }

    public synchronized void touch(long nowMs) {
        this.lastTouchMs = nowMs;
    }

    public synchronized boolean expired(long nowMs) {
        return nowMs - lastTouchMs > IDLE_TTL_MS;
    }

    /** 某商品砍价谈成的价；没砍过返回 null（界面按标价显示） */
    public synchronized Integer agreedPrice(String offerId) {
        return agreedPrices.get(offerId);
    }

    /** 界面该显示/结算的价格：砍过用谈成价，没砍过用标价 */
    public synchronized int effectivePrice(String offerId, int listPrice) {
        Integer agreed = agreedPrices.get(offerId);
        return agreed != null ? agreed : listPrice;
    }

    /** 这个商品在本会话里是否砍过价（至少一轮；详情页据此提示当前已谈到的价） */
    public synchronized boolean hasBargained(String offerId) {
        return bargainedOffers.contains(offerId);
    }

    /**
     * 记下一次砍价结果：成交（accepted 或接受还价）后调用。
     * 多轮砍价（Fong 2026-10-21 要求）：可以反复记，但价格**只降不升**——
     * 新价不比现价低就不覆盖。能降到多少由 BargainPolicy 按标价锚定的底价守死，
     * 这里只管单调性，所以反复砍也砍不穿底价。
     *
     * @return 是否记上了（false = 新价没比现价更低，保持现价）
     */
    public synchronized boolean recordBargain(String offerId, int agreedPrice) {
        int price = Math.max(1, agreedPrice);
        Integer existing = agreedPrices.get(offerId);
        if (existing != null && price >= existing) return false;
        bargainedOffers.add(offerId);
        agreedPrices.put(offerId, price);
        return true;
    }

    /** 某收购商品抬价谈成的价；没谈过返回 null（界面按标价显示） */
    public synchronized Integer agreedPay(String offerId) {
        return agreedPays.get(offerId);
    }

    /** 界面该显示/结算的收购价：抬过用谈成价，没抬过用标价 */
    public synchronized int effectivePay(String offerId, int listPay) {
        Integer agreed = agreedPays.get(offerId);
        return agreed != null ? agreed : listPay;
    }

    /**
     * 记下一次抬价结果（卖货方向）。与砍价镜像：价格**只升不降**——
     * 新价不比现价高就不覆盖。能升到多少由 BargainPolicy 的最高价与村民钱包余额守死。
     *
     * @return 是否记上了（false = 新价没比现价更高，保持现价）
     */
    public synchronized boolean recordRaise(String offerId, int agreedPay) {
        int pay = Math.max(1, agreedPay);
        Integer existing = agreedPays.get(offerId);
        if (existing != null && pay <= existing) return false;
        bargainedOffers.add(offerId);
        agreedPays.put(offerId, pay);
        return true;
    }

    /** 记下一次还价（玩家砍超了，村民只肯让到底线价）；等玩家在界面确认 */
    public synchronized void setPendingCounter(String offerId, int counterPrice) {
        pendingCounters.put(offerId, Math.max(1, counterPrice));
    }

    /** 取某商品的待确认还价；没有返回 null */
    public synchronized Integer pendingCounter(String offerId) {
        return pendingCounters.get(offerId);
    }

    /** 玩家接受/放弃还价后清除 */
    public synchronized void clearPendingCounter(String offerId) {
        pendingCounters.remove(offerId);
    }

    public synchronized String npcLine() {
        return npcLine;
    }

    public synchronized void setNpcLine(String line) {
        this.npcLine = line;
    }

    public synchronized String selectedOfferId() {
        return selectedOfferId;
    }

    public synchronized void select(String offerId) {
        this.selectedOfferId = offerId;
    }
}
