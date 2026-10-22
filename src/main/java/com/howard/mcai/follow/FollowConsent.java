package com.howard.mcai.follow;

/**
 * 跟随裁定（v0.5.0，纯函数可离线测）：玩家叫一个村民跟他走，答不答应由插件按规矩定，
 * 模型只有表达权（答应了怎么说、拒绝了怎么婉拒）。
 * <p>
 * 服主定的规矩：看心情——他没什么事就答应；正忙/睡着/吓坏了/有迫切需求就拒绝；
 * 但玩家可以一直磨：短时间内被拒够次数，他不耐烦了也会不情不愿地跟上（撂下手头事）。
 * 唯一磨不动的：他正在跟别人走——不能把人从别人身边抢走。
 */
public final class FollowConsent {

    public enum Decision {
        AGREE,
        /** 被磨烦了才答应的（撂下手头事，台词要带不情愿） */
        AGREE_GRUDGING,
        REFUSE_BUSY,
        REFUSE_SLEEPING,
        REFUSE_SCARED,
        REFUSE_NEED,
        REFUSE_FOLLOWING_OTHER
    }

    /** 90 秒内被拒达到这个次数，下一次请求直接改口不情愿答应 */
    public static final int GRUDGE_THRESHOLD = 3;
    /** 恐惧达到这个数：吓坏了，不敢乱走 */
    public static final double SCARE_FEAR = 60.0;
    /** 最迫切需求达到这个数：先顾命（饿坏了/困极了），不跟人走 */
    public static final double URGENT_NEED = 85.0;

    private FollowConsent() {
    }

    public static Decision decide(boolean sleeping, boolean busy, boolean followingOther,
                                  double fear, double worstNeed, int recentRefusals) {
        if (followingOther) return Decision.REFUSE_FOLLOWING_OTHER;
        if (recentRefusals >= GRUDGE_THRESHOLD) return Decision.AGREE_GRUDGING;
        if (sleeping) return Decision.REFUSE_SLEEPING;
        if (busy) return Decision.REFUSE_BUSY;
        if (fear >= SCARE_FEAR) return Decision.REFUSE_SCARED;
        if (worstNeed >= URGENT_NEED) return Decision.REFUSE_NEED;
        return Decision.AGREE;
    }

    /** 这个拒绝能不能靠连点磨过去（给界面提示用） */
    public static boolean overridable(Decision d) {
        return d == Decision.REFUSE_BUSY || d == Decision.REFUSE_SLEEPING
                || d == Decision.REFUSE_SCARED || d == Decision.REFUSE_NEED;
    }
}
