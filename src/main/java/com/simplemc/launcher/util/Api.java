package com.simplemc.launcher.util;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 各类外部数据获取（Mojang 版本、Modrinth 模组、加载器版本）。
 */
public final class Api {

    private static final Set<String> APRIL_FOOLS = Set.of(
            "1.RV-Pre1", "3D Shareware v1.34", "15w14a", "20w14infinite",
            "22w13oneBlockAtATime", "23w13a_or_b", "24w14potato", "2.0");

    public record VersionInfo(String id, String type) {
    }

    // ------------------------------------------------------------------
    // Mojang 版本
    // ------------------------------------------------------------------
    public static List<VersionInfo> mojangVersions() throws Exception {
        JsonNode manifest = Downloader.getJson(
                "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");
        List<VersionInfo> result = new ArrayList<>();
        for (JsonNode v : manifest.get("versions")) {
            result.add(new VersionInfo(v.get("id").asText(), v.get("type").asText()));
        }
        return result;
    }

    /** 版本分类：正式版 / 预览版 / 愚人节版 / 远古版。 */
    public static String categoryOf(VersionInfo v) {
        if (APRIL_FOOLS.contains(v.id())) {
            return "愚人节版";
        }
        return switch (v.type()) {
            case "release" -> "正式版";
            case "snapshot" -> "预览版";
            default -> "远古版"; // old_beta / old_alpha
        };
    }

    // ------------------------------------------------------------------
    // Modrinth
    // ------------------------------------------------------------------
    public static JsonNode modrinthSearch(String query, String index, int limit) throws Exception {
        String facets = "[[\"project_type:mod\"]]";
        String url = "https://api.modrinth.com/v2/search?query=" + enc(query)
                + "&limit=" + limit + "&index=" + index + "&facets=" + enc(facets);
        return Downloader.getJson(url);
    }

    /**
     * 按项目类型搜索 Modrinth。
     *
     * @param query       搜索关键词
     * @param projectType 项目类型（mod / shader / datapack / resourcepack / modpack / world）
     */
    public static JsonNode modrinthSearchByType(String query, String projectType) throws Exception {
        String facets = "[[\"project_type:" + projectType + "\"]]";
        String url = "https://api.modrinth.com/v2/search?query=" + enc(query)
                + "&limit=20&index=relevance&facets=" + enc(facets);
        return Downloader.getJson(url);
    }

    // ------------------------------------------------------------------
    // 加载器版本（从最新到最旧）
    // ------------------------------------------------------------------
    public static List<String> fabricLoaderVersions() throws Exception {
        JsonNode loaders = Downloader.getJson("https://meta.fabricmc.net/v2/versions/loader");
        List<String> result = new ArrayList<>();
        for (JsonNode l : loaders) {
            result.add(l.get("version").asText());
        }
        return result;
    }

    public static List<String> quiltLoaderVersions() throws Exception {
        JsonNode loaders = Downloader.getJson("https://meta.quiltmc.org/v3/versions/loader");
        List<String> result = new ArrayList<>();
        for (JsonNode l : loaders) {
            result.add(l.get("version").asText());
        }
        return result;
    }

    public static List<String> forgeVersions() throws Exception {
        List<String> versions = versionsFromXml(Downloader.getString(
                "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml"));
        java.util.Collections.reverse(versions);
        return versions;
    }

    public static List<String> neoforgeVersions() throws Exception {
        List<String> versions = versionsFromXml(Downloader.getString(
                "https://bmclapi2.bangbang93.com/maven/net/neoforged/neoforge/maven-metadata.xml"));
        java.util.Collections.reverse(versions);
        return versions;
    }

    public static List<String> optifineVersions() throws Exception {
        String html = Downloader.getString("https://optifine.net/downloads");
        Pattern p = Pattern.compile("OptiFine_([0-9.]+)_HD_U_([A-Za-z0-9]+)\\.jar");
        Matcher m = p.matcher(html);
        List<String> result = new ArrayList<>();
        Set<String> seen = new java.util.LinkedHashSet<>();
        while (m.find()) {
            seen.add(m.group(1) + "_HD_U_" + m.group(2));
        }
        result.addAll(seen);
        return result;
    }

    // ------------------------------------------------------------------
    private static List<String> versionsFromXml(String xml) {
        List<String> result = new ArrayList<>();
        Matcher m = Pattern.compile("<version>([^<]+)</version>").matcher(xml);
        while (m.find()) {
            result.add(m.group(1));
        }
        return result;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private Api() {
    }
}