package com.howard.mcai.planner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归测试：承诺守卫的交付类判定。
 * <p>
 * 事故样本（v0.4.4 实测）：玩家打劫“给我你身上的所有绿宝石”，村民回
 * “行行行，反正这绿宝石也没几个了，全给你！拿稳了，别摔了！”——本轮没有任何
 * 工具调用、东西纹丝没动，但“全给你”没有主语“我/俺/咱”、不在旧名单里，
 * “拿稳了”更是从没被管过，两句一起漏过守卫，成了如假包换的空头支票。
 */
class PromiseGuardTest {

    @Test
    void subjectlessAllGiveIsUnbacked() {
        assertTrue(PromiseGuard.isUnbackedPromise(
                "行行行，反正这绿宝石也没几个了，全给你！", false, false, false));
    }

    @Test
    void handoverDoneClaimIsUnbacked() {
        assertTrue(PromiseGuard.isUnbackedPromise(
                "拿稳了，别摔了！", false, false, false));
    }

    @Test
    void backedGiveIsKept() {
        // 本轮真的交出去了（reallyGave）→ 同样的话是如实汇报，不许删
        assertFalse(PromiseGuard.isUnbackedPromise(
                "行行行，反正这绿宝石也没几个了，全给你！", true, false, false));
    }

    @Test
    void refusalIsNotAPromise() {
        // 婉拒是老实拒绝：命中婉拒名单的话先被救下，不能当承诺删掉
        assertFalse(PromiseGuard.isUnbackedPromise(
                "俺不能给你，你别想了。", false, false, false));
    }

    @Test
    void plainPleadingIsKept() {
        // 只是求饶/商量、没有交付承诺 → 原样保留（守卫只删中招的那一句）
        assertFalse(PromiseGuard.isUnbackedPromise(
                "行行行，别冲动！你要啥？咱商量着来。", false, false, false));
    }

    @Test
    void priceTalkIsExempt() {
        // 谈价格不是交实物："给你便宜点"归价格让步豁免
        assertFalse(PromiseGuard.isUnbackedPromise(
                "我给你便宜点还不行吗。", false, false, false));
    }

    @Test
    void classicSubjectGiveStillCaught() {
        // 旧形态回归：带主语的"送你/给你"照样要拦
        assertTrue(PromiseGuard.isUnbackedPromise(
                "俺这儿有现成的面包，送你俩。", false, false, false));
    }
}
