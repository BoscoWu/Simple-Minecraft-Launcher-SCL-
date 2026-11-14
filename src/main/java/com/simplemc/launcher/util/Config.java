package com.simplemc.launcher.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 启动器配置。以 JSON 形式保存到 pyLauncher/launcher_config.json。
 * 文件损坏时自动备份并用默认值重建，不会让启动器崩溃。
 */
public final class Config {

    private final Path file;
    private final ObjectNode data;
    private final Map<String, Object> defaults = new LinkedHashMap<>();

    public Config() {
        this(Constants.CONFIG_FILE);
    }

    public Config(Path file) {
        this.file = file;
        this.data = load();

        // 默认值
        defaults.put("current_version", "");
        defaults.put("username", "Steve");
        defaults.put("max_mem", "2048");
        defaults.put("min_mem", "1024");
        defaults.put("jvm_args", "");
        defaults.put("game_args", "");
        defaults.put("java_path", "");
        defaults.put("server_max_mem", "2G");
        defaults.put("server_min_mem", "1G");
        defaults.put("auto_check_update", true);
        defaults.put("window_title", "Minecraft");
        defaults.put("proxy", "");
        defaults.put("auto_memory", true);
        defaults.put("instance_isolation", true);
        defaults.put("process_priority", "普通");
        defaults.put("window_size", "默认");
        defaults.put("auth_mode", "离线");
    }

    private ObjectNode load() {
        try {
            if (Files.isRegularFile(file)) {
                byte[] raw = Files.readAllBytes(file);
                if (raw.length > 0) {
                    JsonNode node = Http.MAPPER.readTree(raw);
                    if (node instanceof ObjectNode obj) {
                        return obj;
                    }
                }
            }
        } catch (IOException e) {
            Log.warn("配置文件读取失败，尝试修复: " + e.getMessage());
            repair();
        }
        return Http.MAPPER.createObjectNode();
    }

    /** 备份损坏的配置并重建 */
    private void repair() {
        try {
            if (Files.isRegularFile(file)) {
                Path bak = file.resolveSibling("launcher_config.corrupt.json");
                Files.copy(file, bak, StandardCopyOption.REPLACE_EXISTING);
                Log.info("已备份损坏的配置到 " + bak.getFileName());
            }
            Files.deleteIfExists(file);
        } catch (Exception e) {
            Log.warn("修复配置失败: " + e.getMessage());
        }
    }

    // ---------------- 读取 ----------------

    public String getString(String key) {
        JsonNode n = data.get(key);
        if (n != null && !n.isNull()) return n.asText();
        Object def = defaults.get(key);
        return def == null ? "" : String.valueOf(def);
    }

    public int getInt(String key) {
        JsonNode n = data.get(key);
        if (n != null && n.isNumber()) return n.asInt();
        try {
            return Integer.parseInt(getString(key));
        } catch (Exception e) {
            return 0;
        }
    }

    public boolean getBoolean(String key) {
        JsonNode n = data.get(key);
        if (n != null && n.isBoolean()) return n.asBoolean();
        Object def = defaults.get(key);
        return def instanceof Boolean b ? b : false;
    }

    // ---------------- 写入 ----------------

    public Config set(String key, Object value) {
        if (value == null) {
            data.putNull(key);
        } else if (value instanceof Integer i) {
            data.put(key, i);
        } else if (value instanceof Long l) {
            data.put(key, l);
        } else if (value instanceof Boolean b) {
            data.put(key, b);
        } else if (value instanceof Double d) {
            data.put(key, d);
        } else {
            data.put(key, String.valueOf(value));
        }
        return this;
    }

    /** 保存到磁盘（原子写入，避免写一半损坏） */
    public synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, Http.MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(data), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            Log.error("保存配置失败", e);
        }
    }

    public JsonNode raw() {
        return data;
    }
}
