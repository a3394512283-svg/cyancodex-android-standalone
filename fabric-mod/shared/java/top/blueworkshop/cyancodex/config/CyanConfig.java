package top.blueworkshop.cyancodex.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import top.blueworkshop.cyancodex.CyanLog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地配置：接口地址、API 密钥、收藏与历史记录。
 * 存放在 .minecraft/config/cyancodex.json
 */
public final class CyanConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String DEFAULT_API = "https://app.blueworkshop.top";

    private static CyanConfig INSTANCE = new CyanConfig();

    /** 接口基址（末尾不带 /） */
    public String apiBase = DEFAULT_API;
    /** 开放 API 密钥（cyx_ 开头） */
    public String apiKey = "";
    /** 收藏的指令原文 */
    public List<String> favorites = new ArrayList<>();
    /** 最近使用过的指令 */
    public List<String> history = new ArrayList<>();
    /** 每个生成器上一次用过的参数 */
    public Map<String, String> lastArgs = new LinkedHashMap<>();

    public static CyanConfig get() {
        return INSTANCE;
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("cyancodex.json");
    }

    public static void load() {
        Path p = file();
        try {
            if (Files.exists(p)) {
                String json = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                CyanConfig loaded = GSON.fromJson(json, CyanConfig.class);
                if (loaded != null) {
                    INSTANCE = loaded;
                }
            }
        } catch (Exception e) {
            CyanLog.LOGGER.warn("[CyanCodex] 读取配置失败，使用默认配置：{}", e.toString());
        }
        if (INSTANCE.apiBase == null || INSTANCE.apiBase.isBlank()) INSTANCE.apiBase = DEFAULT_API;
        INSTANCE.apiBase = INSTANCE.apiBase.replaceAll("/+$", "");
        if (INSTANCE.favorites == null) INSTANCE.favorites = new ArrayList<>();
        if (INSTANCE.history == null) INSTANCE.history = new ArrayList<>();
        if (INSTANCE.lastArgs == null) INSTANCE.lastArgs = new LinkedHashMap<>();
    }

    public static void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.write(file(), GSON.toJson(INSTANCE).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            CyanLog.LOGGER.warn("[CyanCodex] 保存配置失败：{}", e.toString());
        }
    }

    public boolean hasKey() {
        return apiKey != null && apiKey.startsWith("cyx_");
    }

    public void remember(String command) {
        if (command == null || command.isBlank()) return;
        history.remove(command);
        history.add(0, command);
        while (history.size() > 100) history.remove(history.size() - 1);
        save();
    }

    public boolean toggleFavorite(String command) {
        boolean added;
        if (favorites.contains(command)) {
            favorites.remove(command);
            added = false;
        } else {
            favorites.add(0, command);
            added = true;
        }
        save();
        return added;
    }
}
