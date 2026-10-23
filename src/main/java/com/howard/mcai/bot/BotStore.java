package com.howard.mcai.bot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * 假人档案持久化：plugins/MC-AI/bots.json（一个数组，每人一份 BotState）。
 * <p>
 * 写法照村子姓氏档（village_names.json）的路子：整表读写、写失败只记日志不炸插件。
 * 脏标记 + 定时/关服 flush，避免每句话都落盘。
 */
public final class BotStore {

    private static final Type LIST_TYPE = new TypeToken<List<BotState>>() {}.getType();

    private final Path file;
    private final Logger logger;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private volatile boolean dirty;

    public BotStore(Path dataFolder, Logger logger) {
        this.file = dataFolder.resolve("bots.json");
        this.logger = logger;
    }

    public List<BotState> load() {
        if (!Files.exists(file)) return new ArrayList<>();
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            List<BotState> list = gson.fromJson(text, LIST_TYPE);
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            logger.warning("[MC-AI] bots.json 读取失败（按空档案启动）: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    public void markDirty() { dirty = true; }

    public boolean isDirty() { return dirty; }

    public void save(List<BotState> states) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, gson.toJson(new ArrayList<>(states)), StandardCharsets.UTF_8);
            dirty = false;
        } catch (IOException e) {
            logger.warning("[MC-AI] bots.json 保存失败: " + e.getMessage());
        }
    }
}
