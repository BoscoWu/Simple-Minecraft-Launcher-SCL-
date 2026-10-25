package com.simplemc.launcher.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 启动器配置（基于 JSON 对象，损坏时自动重置）。
 */
public class Config {

    private final ObjectNode data;

    public Config(ObjectNode data) {
        this.data = data;
    }

    public static Config load(Path file) {
        ObjectNode data = JsonUtil.newObject();
        applyDefaults(data);
        if (Files.exists(file)) {
            try {
                JsonNode n = JsonUtil.MAPPER.readTree(Files.readString(file));
                if (n != null && n.isObject()) {
                    ((ObjectNode) n).fields().forEachRemaining(e -> data.set(e.getKey(), e.getValue()));
                }
            } catch (Exception e) {
                Log.warn("配置文件损坏，已重置为默认配置: " + e.getMessage());
                try {
                    Files.copy(file, file.resolveSibling(file.getFileName() + ".corrupt.bak"),
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception ignored) {
                }
            }
        }
        return new Config(data);
    }

    private static void applyDefaults(ObjectNode data) {
        data.put("player_name", "Player");
        data.put("current_version", "");
        data.put("current_loader", "vanilla");
        data.put("original_version", "");
        data.putNull("ms_refresh_token");
        data.putNull("ms_username");
        data.putNull("ms_uuid");
        data.putNull("ms_access_token");
        data.put("max_memory", "2G");
        data.put("min_memory", "1G");
        data.put("download_source", "bmcl"); // 默认使用 BMCL 镜像，国内速度更快
    }

    public void save(Path file) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, JsonUtil.stringifyPretty(data));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            Log.error("保存配置失败", e);
        }
    }

    public String getString(String key) {
        JsonNode n = data.get(key);
        return (n == null || n.isNull()) ? "" : n.asText();
    }

    public String getString(String key, String def) {
        String v = getString(key);
        return v.isEmpty() ? def : v;
    }

    public void put(String key, String value) {
        data.put(key, value);
    }

    public void putNull(String key) {
        data.putNull(key);
    }

    public ObjectNode data() {
        return data;
    }
}