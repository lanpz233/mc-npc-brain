package com.howard.mcai.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 免费赠送止血：price=0 不再凭空造物 —— 非神使给多少完全由真实库存决定。
 */
class WorldInteractionServiceTest {

    @Test
    void freeGiftCappedByStockForNonEnvoy() {
        assertEquals(3, WorldInteractionService.freeGiftAmount(16, 3, false));
        assertEquals(2, WorldInteractionService.freeGiftAmount(2, 10, false));
    }

    @Test
    void freeGiftZeroWhenNoStock() {
        assertEquals(0, WorldInteractionService.freeGiftAmount(5, 0, false));
    }

    @Test
    void freeGiftEnvoyMaySpawnButStillCapped() {
        // 神使可凭空补足，但单次上限 FREE_GIFT_MAX 仍然有效
        assertEquals(5, WorldInteractionService.freeGiftAmount(5, 0, true));
        assertEquals(WorldInteractionService.FREE_GIFT_MAX,
                WorldInteractionService.freeGiftAmount(64, 0, true));
    }

    @Test
    void freeGiftRejectsNonPositiveRequest() {
        assertEquals(0, WorldInteractionService.freeGiftAmount(0, 10, false));
        assertEquals(0, WorldInteractionService.freeGiftAmount(-3, 10, true));
    }
}
