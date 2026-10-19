package com.howard.mcai.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 神使审计日志（SPEC 2.14）—— 神使的每一次世界操作都追加写入 audit.log。
 * <p>
 * 文件位置：plugins/MC-AI/audit.log，每行一条：
 * {@code [2026-10-19 18:00:00] NPC=王铁匠(uuid前8位) action=break_block params={x=1,y=64,z=2} result=OK}
 * <p>
 * 设计要点：
 * <ul>
 *   <li><b>append-only</b>：只追加不轮转，服主直接用文本编辑器/tail 查看</li>
 *   <li><b>同步写</b>：神使操作是低频事件（人为授予才有），同步 append 的 IO 开销可忽略，
 *       换来"写完才算执行完"的审计强度 —— 不做异步缓冲，避免崩溃丢审计记录</li>
 *   <li>拒绝（REFUSED）的神使操作也记录：留痕比省事重要</li>
 * </ul>
 */
public final class AuditLog {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path file;

    public AuditLog(Path dataFolder) {
        this.file = dataFolder.resolve("audit.log");
    }

    /** 文件路径（/ai envoy list 等命令展示用） */
    public Path file() {
        return file;
    }

    /**
     * 追加一条审计记录。
     *
     * @param npcName NPC 显示名
     * @param npcUuid NPC 实体 UUID
     * @param action  动作名（break_block / place_block / teleport_to / give_item / ENVOY_GRANT ...）
     * @param params  参数摘要（如 "x=1,y=64,z=2" 或 "item=钻石剑,count=1,target=Steve"）
     * @param result  OK / REFUSED(原因) / CONFIRM_PENDING
     */
    public void log(String npcName, java.util.UUID npcUuid, String action, String params, String result) {
        String line = "[" + LocalDateTime.now().format(TS) + "] NPC=" + npcName
                + "(" + npcUuid.toString().substring(0, 8) + ")"
                + " action=" + action
                + " params={" + params + "}"
                + " result=" + result;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, line + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // 审计写失败不能中断 NPC 行为，但要大声报出来让服主知道审计链断了
            System.err.println("[MC-AI] audit.log 写入失败: " + e.getMessage());
        }
    }
}
