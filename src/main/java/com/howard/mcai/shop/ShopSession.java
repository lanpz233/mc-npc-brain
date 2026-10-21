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

    /** 砍价谈成的价：offerId → 成交价（绿宝石）。同一商品一会话只砍一次（bargainedOffers 标记） */
    private final Map<String, Integer> agreedPrices = new HashMap<>();
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

    /** 这个商品在本会话里是否已经砍过价（砍过就锁定，不许叠砍） */
    public synchronized boolean hasBargained(String offerId) {
        return bargainedOffers.contains(offerId);
    }

    /**
     * 记下一次砍价结果：成交（accepted 或接受还价）后调用，价格锁定到会话结束。
     * 已砍过的商品再次调用不覆盖 —— 一会话一商品只砍一次。
     *
     * @return 是否记上了（false = 之前已经砍过，保持原价）
     */
    public synchronized boolean recordBargain(String offerId, int agreedPrice) {
        if (bargainedOffers.contains(offerId)) return false;
        bargainedOffers.add(offerId);
        agreedPrices.put(offerId, Math.max(1, agreedPrice));
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
