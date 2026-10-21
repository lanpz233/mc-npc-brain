package com.howard.mcai.shop;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 交易会话登记处 —— 按（村民，玩家）二元组存会话。
 * 打开商店、砍价、关窗重开都来这里取同一份进度；闲置超时顺手清理，防内存无限涨。
 */
public final class ShopSessionManager {

    private record Key(UUID npcUuid, UUID playerUuid) {
    }

    private final Map<Key, ShopSession> sessions = new ConcurrentHashMap<>();

    /** 取该玩家在该村民处的会话；没有（或已过期）就新建一份 */
    public ShopSession getOrCreate(UUID npcUuid, UUID playerUuid) {
        long now = System.currentTimeMillis();
        Key key = new Key(npcUuid, playerUuid);
        ShopSession existing = sessions.get(key);
        if (existing != null && !existing.expired(now)) {
            existing.touch(now);
            return existing;
        }
        ShopSession fresh = new ShopSession(npcUuid, playerUuid, now);
        sessions.put(key, fresh);
        return fresh;
    }

    /** 只取不建（结算复核等场景不该凭空造会话）；过期视为不存在 */
    public ShopSession get(UUID npcUuid, UUID playerUuid) {
        ShopSession s = sessions.get(new Key(npcUuid, playerUuid));
        if (s == null || s.expired(System.currentTimeMillis())) return null;
        return s;
    }

    /** 玩家下线/村民移除时清掉其会话 */
    public void remove(UUID npcUuid, UUID playerUuid) {
        sessions.remove(new Key(npcUuid, playerUuid));
    }

    /** 清掉某个村民的全部会话（村民被移除时用） */
    public void removeNpc(UUID npcUuid) {
        sessions.keySet().removeIf(k -> k.npcUuid().equals(npcUuid));
    }

    /** 顺手清理全部过期会话（开店时调用即可，不另起定时器） */
    public void pruneExpired() {
        long now = System.currentTimeMillis();
        sessions.values().removeIf(s -> s.expired(now));
    }

    public int size() {
        return sessions.size();
    }
}
