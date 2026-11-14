package com.simplemc.launcher.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.simplemc.launcher.util.Constants;
import com.simplemc.launcher.util.Http;
import com.simplemc.launcher.util.Log;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Modrinth 内容搜索与下载。
 */
public final class Modrinth {

    private static final String API = "https://api.modrinth.com/v2";

    /** 内容类别 */
    public enum Category {
        MOD("模组", "mod"),
        MODPACK("整合包", "modpack"),
        DATAPACK("数据包", "datapack"),
        RESOURCEPACK("资源包", "resourcepack"),
        SHADER("光影包", "shader"),
        WORLD("世界", null);

        public final String display;
        public final String projectType;
        Category(String d, String t) { display = d; projectType = t; }
    }

    /** 搜索结果条目 */
    public record Hit(String id, String slug, String title, String description,
                      String iconUrl, long downloads, String author) {}

    /** 文件条目 */
    public record FileEntry(String url, String filename, String sha1, long size) {}

    private Modrinth() {}

    /**
     * 搜索内容。
     *
     * @param category      类别
     * @param query         关键词（可为空）
     * @param gameVersion   游戏版本过滤（可为 null）
     * @param loader        加载器过滤（可为 null）
     * @param limit         返回条数
     */
    public static List<Hit> search(Category category, String query,
                                   String gameVersion, String loader, int limit)
            throws Exception {
        StringBuilder url = new StringBuilder(API).append("/search?limit=").append(limit);

        if (category.projectType != null) {
            String facets = "[[\"project_type:" + category.projectType + "\"]]";
            url.append("&facets=").append(URLEncoder.encode(facets, StandardCharsets.UTF_8));
        }

        if (query != null && !query.isBlank()) {
            url.append("&query=").append(URLEncoder.encode(query.trim(), StandardCharsets.UTF_8));
        } else if (category == Category.WORLD) {
            url.append("&query=").append(URLEncoder.encode("world", StandardCharsets.UTF_8));
        }

        List<String> extraFacets = new ArrayList<>();
        if (gameVersion != null && !gameVersion.isBlank()) {
            extraFacets.add("[\"versions:" + gameVersion + "\"]");
        }
        if (loader != null && !loader.isBlank()) {
            extraFacets.add("[\"categories:" + loader.toLowerCase() + "\"]");
        }
        if (!extraFacets.isEmpty() && category.projectType != null) {
            String facets = "[[\"project_type:" + category.projectType + "\"],"
                    + String.join(",", extraFacets) + "]";
            // 重建 URL 里的 facets
            String head = url.substring(0, url.indexOf("&facets="));
            String tail = url.substring(url.indexOf("&facets="));
            int amp = tail.indexOf('&', 1);
            tail = amp > 0 ? tail.substring(amp) : "";
            url = new StringBuilder(head)
                    .append("&facets=").append(URLEncoder.encode(facets, StandardCharsets.UTF_8))
                    .append(tail);
        }

        JsonNode resp = Http.fetchJson(url.toString(), Duration.ofSeconds(25), null);
        List<Hit> hits = new ArrayList<>();
        JsonNode arr = resp.path("hits");
        if (arr.isArray()) {
            for (JsonNode h : arr) {
                hits.add(new Hit(
                        h.path("project_id").asText(""),
                        h.path("slug").asText(""),
                        h.path("title").asText("?"),
                        h.path("description").asText(""),
                        h.path("icon_url").asText(""),
                        h.path("downloads").asLong(0),
                        h.path("author").asText("")
                ));
            }
        }
        return hits;
    }

    /**
     * 获取某个项目最新可用的文件。
     */
    public static FileEntry latestFile(String slugOrId, String gameVersion, String loader)
            throws Exception {
        StringBuilder url = new StringBuilder(API)
                .append("/project/").append(slugOrId).append("/version");
        List<String> q = new ArrayList<>();
        if (gameVersion != null && !gameVersion.isBlank()) {
            q.add("game_versions=" + URLEncoder.encode("[\"" + gameVersion + "\"]",
                    StandardCharsets.UTF_8));
        }
        if (loader != null && !loader.isBlank()) {
            q.add("loaders=" + URLEncoder.encode("[\"" + loader.toLowerCase() + "\"]",
                    StandardCharsets.UTF_8));
        }
        if (!q.isEmpty()) url.append('?').append(String.join("&", q));

        JsonNode arr = Http.fetchJson(url.toString(), Duration.ofSeconds(25), null);
        if (!arr.isArray() || arr.isEmpty()) {
            return null;
        }
        JsonNode first = arr.get(0);
        JsonNode files = first.path("files");
        if (!files.isArray() || files.isEmpty()) return null;

        // 优先选 primary 文件
        JsonNode chosen = files.get(0);
        for (JsonNode f : files) {
            if (f.path("primary").asBoolean(false)) {
                chosen = f;
                break;
            }
        }
        return new FileEntry(
                chosen.path("url").asText(""),
                chosen.path("filename").asText(""),
                chosen.path("hashes").path("sha1").asText(""),
                chosen.path("size").asLong(0)
        );
    }

    /** 获取项目详情 */
    public static JsonNode project(String slugOrId) throws Exception {
        return Http.fetchJson(API + "/project/" + slugOrId, Duration.ofSeconds(20), null);
    }

    /** 获取所有版本 */
    public static JsonNode versions(String slugOrId) throws Exception {
        return Http.fetchJson(API + "/project/" + slugOrId + "/version",
                Duration.ofSeconds(20), null);
    }

    /** 下载量格式化 */
    public static String formatDownloads(long n) {
        if (n >= 100_000_000L) return String.format("%.1f亿", n / 100_000_000.0);
        if (n >= 10_000L) return String.format("%.1f万", n / 10_000.0);
        return String.valueOf(n);
    }
}
