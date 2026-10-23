package com.howard.mcai.ai;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 每村一个主姓（Fong 2026-10-23 定）—— 修“全服都姓李”：
 * 同村主姓稳定、不同村不撞姓、落盘后重启不变。
 */
class NameLibraryVillageTest {

    @Test
    void perVillageSurnames() throws Exception {
        Path dir = Files.createTempDirectory("mcai-names");
        NameLibrary.load(dir);
        assertTrue(NameLibrary.isLoaded());

        String first = NameLibrary.surnameForVillage(101);
        assertNotNull(first);
        // 同一个村问多少次都是这个姓
        assertEquals(first, NameLibrary.surnameForVillage(101));
        // 第二个村抽姓时避开已有村子的姓
        String second = NameLibrary.surnameForVillage(202);
        assertNotNull(second);
        assertNotEquals(first, second);
        assertEquals(second, NameLibrary.surnameForVillage(202));

        // 落盘：重新加载（模拟重启）后两村的姓都不变
        NameLibrary.load(dir);
        assertEquals(first, NameLibrary.surnameForVillage(101));
        assertEquals(second, NameLibrary.surnameForVillage(202));

        // 按村生成全名：非空、至少姓+一个字
        String name = NameLibrary.fullName("M", 101);
        assertTrue(name.length() >= 2);
    }

    @Test
    void legacyGlobalSurnameStillWorks() throws Exception {
        Path dir = Files.createTempDirectory("mcai-names-legacy");
        NameLibrary.load(dir);
        assertTrue(NameLibrary.isLoaded());
        // 不知道在哪个村（-1）→ 全局回退主姓，不为 null
        assertNotNull(NameLibrary.surnameForVillage(-1));
        assertTrue(NameLibrary.fullName("F").length() >= 2);
    }
}
