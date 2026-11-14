package com.simplemc.launcher.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.simplemc.launcher.util.Config;
import com.simplemc.launcher.util.Constants;
import com.simplemc.launcher.util.Downloader;
import com.simplemc.launcher.util.Http;
import com.simplemc.launcher.util.Log;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Minecraft 版本管理：拉取版本清单、列出已安装版本。
 */
public final class VersionManager {

    /** 国内镜像（BMCLAPI），加速游戏资源下载 */
    public static final String BMCLAPI_BASE = "https://bmclapi2.bangbang93.com";
    public static final String MOJANG_MANIFEST =
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    public static final String BMCLAPI_MANIFEST = BMCLAPI_BASE + "/mc/game/version_manifest_v2.json";

    private final Config config;
    private List<VersionInfo> cachedAll;
    private long cachedAt;

    public VersionManager(Config config) {
        this.config = config;
    }

    /** 版本信息 */
    public record VersionInfo(String id, String type, String url, String releaseTime) {
        public String typeName() {
            return switch (type) {
                case "release" -> "正式版";
                case "snapshot" -> "预览版";
                case "old_beta", "old_alpha" -> "远古版";
                default -> type;
            };
        }
    }

    /**
     * 获取全部版本列表（带缓存）。
     */
    public List<VersionInfo> listAllVersions() throws Exception {
        if (cachedAll != null && System.currentTimeMillis() - cachedAt < 10 * 60 * 1000L) {
            return cachedAll;
        }
        JsonNode manifest = null;
        // 先试国内镜像
        try {
            manifest = Http.fetchJson(BMCLAPI_MANIFEST);
        } catch (Exception e) {
            Log.warn("BMCLAPI 清单失败: " + e.getMessage());
        }
        if (manifest == null) {
            // 退回官方
            manifest = Http.fetchJson(MOJANG_MANIFEST);
        }

        List<VersionInfo> list = new ArrayList<>();
        JsonNode arr = manifest.get("versions");
        if (arr != null && arr.isArray()) {
            for (JsonNode v : arr) {
                list.add(new VersionInfo(
                        v.path("id").asText(),
                        v.path("type").asText(),
                        v.path("url").asText(),
                        v.path("releaseTime").asText()));
            }
        }
        cachedAll = list;
        cachedAt = System.currentTimeMillis();
        return list;
    }

    /** 最新正式版 */
    public String latestRelease() throws Exception {
        for (VersionInfo v : listAllVersions()) {
            if ("release".equals(v.type())) return v.id();
        }
        return listAllVersions().isEmpty() ? "" : listAllVersions().get(0).id();
    }

    /** 已安装的版本 ID 列表 */
    public List<String> installedVersions() {
        List<String> out = new ArrayList<>();
        Path vdir = Constants.MC_DIR.resolve("versions");
        if (!Files.isDirectory(vdir)) return out;
        try (var stream = Files.list(vdir)) {
            stream.filter(Files::isDirectory)
                    .forEach(p -> {
                        Path json = p.resolve(p.getFileName() + ".json");
                        if (Files.isRegularFile(json)) {
                            out.add(p.getFileName().toString());
                        }
                    });
        } catch (Exception e) {
            Log.warn("枚举版本失败: " + e.getMessage());
        }
        out.sort(Comparator.reverseOrder());
        return out;
    }

    /** 读取某个版本的 JSON */
    public JsonNode readVersionJson(String versionId) throws Exception {
        Path json = Constants.MC_DIR.resolve("versions")
                .resolve(versionId).resolve(versionId + ".json");
        if (!Files.isRegularFile(json)) {
            throw new IllegalStateException("版本 " + versionId + " 未安装");
        }
        return Http.MAPPER.readTree(Files.readAllBytes(json));
    }

    /** 获取版本清单中某个版本的 URL */
    public String versionUrl(String versionId) throws Exception {
        for (VersionInfo v : listAllVersions()) {
            if (v.id().equals(versionId)) return v.url();
        }
        return null;
    }

    /** 是否为原版（没有 inheritsFrom） */
    public static boolean isVanilla(JsonNode versionJson) {
        return versionJson.path("inheritsFrom").isMissingNode()
                || versionJson.path("inheritsFrom").asText().isBlank();
    }

    /** 从加载器版本名里剥离后缀，得到原版版本号 */
    public static String stripLoaderSuffix(String id) {
        if (id == null) return "";
        // fabric-loader-0.16.9-1.21.1
        String[] patterns = {
                "fabric-loader-[^-]+-", "quilt-loader-[^-]+-",
                "forge-", "neoforge-", "optifine-"
        };
        for (String p : patterns) {
            if (id.matches(p + ".*")) {
                return id.replaceAll("^" + p, "");
            }
        }
        return id;
    }
}
