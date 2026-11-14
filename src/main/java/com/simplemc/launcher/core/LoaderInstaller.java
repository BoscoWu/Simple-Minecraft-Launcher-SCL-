package com.simplemc.launcher.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simplemc.launcher.util.Constants;
import com.simplemc.launcher.util.Downloader;
import com.simplemc.launcher.util.Http;
import com.simplemc.launcher.util.Log;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 模组加载器安装：Fabric / Quilt / Forge / NeoForge / OptiFine。
 */
public final class LoaderInstaller {

    public enum Loader {
        VANILLA("原版"),
        FABRIC("Fabric"),
        QUILT("Quilt"),
        FORGE("Forge"),
        NEOFORGE("NeoForge"),
        OPTIFINE("OptiFine");

        public final String display;
        Loader(String d) { this.display = d; }
    }

    private final Path mcDir;

    public LoaderInstaller(Path mcDir) {
        this.mcDir = mcDir;
    }

    // ---------------- Fabric / Quilt ----------------

    public record LoaderVersion(String version, boolean stable) {}

    /** 列出某个加载器的可用版本 */
    public List<LoaderVersion> listLoaderVersions(Loader loader) throws Exception {
        return listLoaderVersions(loader, null);
    }

    /**
     * 列出某个加载器的可用版本（可按 Minecraft 版本过滤）。
     *
     * @param mcVersion 仅对 Forge / NeoForge 有意义；为 null 时返回全部
     */
    public List<LoaderVersion> listLoaderVersions(Loader loader, String mcVersion)
            throws Exception {
        return switch (loader) {
            case FABRIC, QUILT -> listFabricLikeVersions(loader);
            case FORGE -> listForgeVersions(mcVersion);
            case NEOFORGE -> listNeoForgeVersions(mcVersion);
            default -> List.of();
        };
    }

    private List<LoaderVersion> listFabricLikeVersions(Loader loader) throws Exception {
        String url = switch (loader) {
            case FABRIC -> "https://meta.fabricmc.net/v2/versions/loader";
            case QUILT -> "https://meta.quiltmc.org/v3/versions/loader";
            default -> throw new IllegalArgumentException("不支持的加载器: " + loader);
        };
        JsonNode arr = Http.fetchJson(url);
        List<LoaderVersion> out = new ArrayList<>();
        if (arr.isArray()) {
            for (JsonNode n : arr) {
                out.add(new LoaderVersion(
                        n.path("version").asText(),
                        n.path("stable").asBoolean(false)));
            }
        }
        return out;
    }

    /**
     * 列出 Forge 版本。
     *
     * <p>用官方 promotions 接口拿 recommended/latest，
     * 再用 maven-metadata.xml 列出全部版本。</p>
     */
    public List<LoaderVersion> listForgeVersions(String mcVersion) {
        List<LoaderVersion> out = new ArrayList<>();
        try {
            // 1. 先从 promotions 拿推荐版和最新版
            String recommended = null, latest = null;
            try {
                JsonNode promos = Http.fetchJson(
                        "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json",
                        java.time.Duration.ofSeconds(15), null);
                if (mcVersion != null) {
                    recommended = promos.path("promos").path(mcVersion + "-recommended").asText("");
                    latest = promos.path("promos").path(mcVersion + "-latest").asText("");
                }
            } catch (Exception e) {
                Log.warn("Forge promotions 获取失败: " + e.getMessage());
            }

            // 2. 从 maven-metadata 列出全部版本
            String xml = new String(Http.get(
                    "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml",
                    java.time.Duration.ofSeconds(20)).body(),
                    java.nio.charset.StandardCharsets.UTF_8);
            var m = java.util.regex.Pattern.compile("<version>([^<]+)</version>").matcher(xml);
            List<String> all = new ArrayList<>();
            while (m.find()) all.add(m.group(1).trim());

            // 3. 过滤出该 Minecraft 版本对应的（格式：<mc>-<forge>）
            for (String full : all) {
                if (mcVersion != null && !full.startsWith(mcVersion + "-")) continue;
                String fv = mcVersion != null ? full.substring(mcVersion.length() + 1) : full;
                boolean stable = fv.equals(recommended) || fv.equals(latest);
                out.add(new LoaderVersion(fv, stable));
            }
            // 最新在前（maven 元数据一般已按顺序，这里再倒序一次保证）
            java.util.Collections.reverse(out);
        } catch (Exception e) {
            Log.warn("Forge 版本列表获取失败: " + e.getMessage());
        }
        return out;
    }

    /** 列出 NeoForge 版本 */
    public List<LoaderVersion> listNeoForgeVersions(String mcVersion) {
        List<LoaderVersion> out = new ArrayList<>();
        // maven.neoforged.net 在国内常连接超时，优先走 BMCLAPI 镜像
        String[] urls = {
                VersionManager.BMCLAPI_BASE
                        + "/maven/net/neoforged/neoforge/maven-metadata.xml",
                "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml"
        };
        String xml = null;
        for (String url : urls) {
            try {
                xml = new String(Http.get(url, java.time.Duration.ofSeconds(15)).body(),
                        java.nio.charset.StandardCharsets.UTF_8);
                if (xml != null && xml.contains("<version>")) {
                    Log.info("NeoForge 元数据来源: " + url);
                    break;
                }
                xml = null;
            } catch (Exception e) {
                Log.warn("NeoForge 元数据获取失败 " + url + ": " + e.getMessage());
            }
        }
        if (xml == null) {
            Log.warn("NeoForge 版本列表获取失败：所有源均不可用");
            return out;
        }
        try {
            var m = java.util.regex.Pattern.compile("<version>([^<]+)</version>").matcher(xml);
            List<String> all = new ArrayList<>();
            while (m.find()) all.add(m.group(1).trim());

            // NeoForge 版本号规则：MC 1.21.1 → 21.1.x
            String prefix = null;
            if (mcVersion != null) {
                String[] parts = mcVersion.split("\\.");
                if (parts.length >= 2) {
                    String minor = parts[1];
                    String patch = parts.length >= 3 ? parts[2].replaceAll("\\D.*", "") : "0";
                    prefix = minor + "." + patch + ".";
                }
            }
            for (int i = all.size() - 1; i >= 0; i--) {
                String v = all.get(i);
                if (prefix != null && !v.startsWith(prefix)) continue;
                boolean stable = !v.contains("beta") && !v.contains("alpha");
                out.add(new LoaderVersion(v, stable));
            }
        } catch (Exception e) {
            Log.warn("NeoForge 版本列表解析失败: " + e.getMessage());
        }
        return out;
    }

    /** 安装 Fabric / Quilt */
    public String installFabricLike(Loader loader, String mcVersion, String loaderVersion,
                                    Consumer<String> stage) throws Exception {
        String base = switch (loader) {
            case FABRIC -> "https://meta.fabricmc.net/v2/versions/loader";
            case QUILT -> "https://meta.quiltmc.org/v3/versions/loader";
            default -> throw new IllegalArgumentException("不支持的加载器");
        };
        String metaBase = switch (loader) {
            case FABRIC -> "https://meta.fabricmc.net/v2/versions/loader";
            case QUILT -> "https://meta.quiltmc.org/v3/versions/loader";
            default -> base;
        };

        // 1. 确保原版已安装
        stage.accept("检查原版 " + mcVersion);
        ensureVanilla(mcVersion, stage);

        // 2. 选加载器版本
        String lv = loaderVersion;
        if (lv == null || lv.isBlank() || "最新".equals(lv)) {
            List<LoaderVersion> all = listLoaderVersions(loader);
            lv = all.stream().filter(LoaderVersion::stable)
                    .findFirst().orElse(all.isEmpty() ? null : all.get(0)).version();
        }
        if (lv == null) throw new IllegalStateException("找不到可用的加载器版本");

        // 3. 拉取 profile JSON
        stage.accept("获取 " + loader.display + " " + lv + " 配置...");
        String profileUrl = metaBase + "/" + mcVersion + "/" + lv + "/profile/json";
        JsonNode profile = Http.fetchJson(profileUrl);
        if (profile == null || profile.path("id").isMissingNode()) {
            throw new IllegalStateException("该版本组合不存在: " + mcVersion + " + " + lv);
        }

        // 4. 强制写 inheritsFrom，指向原版
        ObjectNode obj = ((ObjectNode) profile).deepCopy();
        obj.put("inheritsFrom", mcVersion);
        String newId = obj.path("id").asText();

        // 写入可读的显示名（如 "1.21.1 - Fabric 0.20.1"），供实例列表展示
        String displayName = mcVersion + " - " + loader.display + " " + lv;
        ObjectNode meta = obj.putObject("sclModpack");
        meta.put("name", displayName);
        meta.put("loader", loader.name().toLowerCase());
        meta.put("loaderVersion", lv);
        meta.put("mcVersion", mcVersion);
        meta.put("createdAt", System.currentTimeMillis());

        Path dir = mcDir.resolve("versions").resolve(newId);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(newId + ".json"),
                Http.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(obj),
                StandardCharsets.UTF_8);

        // 4. 下载该加载器需要但原版没有的库（Fabric 的 libraries 只有 name）
        stage.accept("下载 " + loader.display + " 依赖库...");
        VanillaInstaller installer = new VanillaInstaller(mcDir);
        installer.installLibrariesOnly(obj,
                new VanillaInstaller.Progress() {
                    @Override public void onStage(String s) { stage.accept(s); }
                    @Override public void onProgress(long c, long t) {}
                });

        stage.accept(loader.display + " " + lv + " 安装完成（" + displayName + "）");
        Log.info("已安装 " + newId + " -> " + displayName);
        return newId;
    }

    // ---------------- Forge / NeoForge ----------------

    /** 安装 Forge / NeoForge（调用官方安装器） */
    public String installForgeLike(Loader loader, String mcVersion, String loaderVersion,
                                   String javaBin, Consumer<String> stage) throws Exception {
        stage.accept("检查原版 " + mcVersion);
        ensureVanilla(mcVersion, stage);

        String lv = loaderVersion;
        String installerUrl;
        String expectedId;

        if (loader == Loader.FORGE) {
            if (lv == null || lv.isBlank() || "最新".equals(lv)) {
                JsonNode promos = Http.fetchJson(
                        "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json");
                lv = promos.path("promos").path(mcVersion + "-latest").asText("");
                if (lv.isBlank()) {
                    lv = promos.path("promos").path(mcVersion + "-recommended").asText("");
                }
                if (lv.isBlank()) {
                    throw new IllegalStateException("没有 " + mcVersion + " 对应的 Forge 版本");
                }
            }
            installerUrl = "https://maven.minecraftforge.net/net/minecraftforge/forge/"
                    + mcVersion + "-" + lv + "/forge-" + mcVersion + "-" + lv + "-installer.jar";
            expectedId = mcVersion + "-forge-" + lv;
        } else {
            // NeoForge
            if (lv == null || lv.isBlank() || "最新".equals(lv)) {
                lv = latestNeoForgeVersion(mcVersion);
            }
            installerUrl = "https://maven.neoforged.net/releases/net/neoforged/neoforge/"
                    + lv + "/neoforge-" + lv + "-installer.jar";
            expectedId = "neoforge-" + lv;
        }

        // 下载安装器（NeoForge 尝试 BMCLAPI 镜像，官方站国内常超时）
        stage.accept("下载 " + loader.display + " 安装器...");
        Path cache = Constants.DATA_DIR.resolve("cache");
        Files.createDirectories(cache);
        Path installer = cache.resolve(loader.name().toLowerCase() + "-installer.jar");

        List<String> installerUrls = new ArrayList<>();
        if (loader == Loader.NEOFORGE) {
            installerUrls.add(VersionManager.BMCLAPI_BASE
                    + "/maven/net/neoforged/neoforge/" + lv
                    + "/neoforge-" + lv + "-installer.jar");
        }
        installerUrls.add(installerUrl);
        Downloader.download(installerUrls, installer, null, null);

        // 运行安装器
        stage.accept("运行 " + loader.display + " 安装器（可能需要几分钟）...");
        ProcessBuilder pb = new ProcessBuilder(
                javaBin, "-jar", installer.toAbsolutePath().toString(),
                "--installClient",
                mcDir.toAbsolutePath().toString());
        pb.directory(mcDir.toFile());
        pb.redirectErrorStream(true);
        Path installLog = cache.resolve(loader.name().toLowerCase() + "-install.log");
        pb.redirectOutput(ProcessBuilder.Redirect.to(installLog.toFile()));

        Process p = pb.start();
        boolean finished = p.waitFor(15, TimeUnit.MINUTES);
        if (!finished) {
            p.destroyForcibly();
            throw new IllegalStateException(loader.display + " 安装超时");
        }
        if (p.exitValue() != 0) {
            String tail = tailOf(installLog, 30);
            throw new IllegalStateException(loader.display + " 安装失败 (exit "
                    + p.exitValue() + ")\n" + tail);
        }

        // 找出实际生成的版本目录
        String actualId = findInstalledVersionId(expectedId);
        if (actualId == null) {
            throw new IllegalStateException(loader.display + " 安装完成但找不到版本目录，期望: "
                    + expectedId);
        }
        // 写入可读显示名
        String displayName = mcVersion + " - " + loader.display + " " + lv;
        writeDisplayName(actualId, displayName, loader, lv, mcVersion);
        stage.accept(loader.display + " 安装完成: " + displayName);
        return actualId;
    }

    /** 往已存在的版本 JSON 里写入 sclModpack 显示信息 */
    private void writeDisplayName(String versionId, String displayName,
                                  Loader loader, String loaderVersion, String mcVersion) {
        try {
            Path json = mcDir.resolve("versions").resolve(versionId)
                    .resolve(versionId + ".json");
            if (!Files.isRegularFile(json)) return;
            JsonNode node = Http.MAPPER.readTree(Files.readAllBytes(json));
            if (node instanceof ObjectNode obj) {
                ObjectNode meta = obj.withObject("/sclModpack");
                meta.put("name", displayName);
                meta.put("loader", loader.name().toLowerCase());
                meta.put("loaderVersion", loaderVersion == null ? "" : loaderVersion);
                meta.put("mcVersion", mcVersion);
                meta.put("createdAt", System.currentTimeMillis());
                Files.writeString(json,
                        Http.MAPPER.writerWithDefaultPrettyPrinter()
                                .writeValueAsString(obj),
                        StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            Log.warn("写入显示名失败 " + versionId + ": " + e.getMessage());
        }
    }

    private String latestNeoForgeVersion(String mcVersion) throws Exception {
        // NeoForge 版本号规则：1.21.1 -> 21.1.x
        String[] parts = mcVersion.split("\\.");
        if (parts.length < 2) throw new IllegalStateException("无法解析版本 " + mcVersion);
        String prefix = parts[1];
        String minor = parts.length >= 3 ? parts[2] : "0";
        String base = prefix + "." + minor + ".";

        // 优先 BMCLAPI 镜像
        String[] urls = {
                VersionManager.BMCLAPI_BASE
                        + "/maven/net/neoforged/neoforge/maven-metadata.xml",
                "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml"
        };
        String xml = null;
        for (String u : urls) {
            try {
                xml = new String(Http.get(u, Duration.ofSeconds(15)).body(),
                        StandardCharsets.UTF_8);
                if (xml != null && xml.contains("<version>")) break;
                xml = null;
            } catch (Exception e) {
                Log.warn("NeoForge 元数据失败 " + u + ": " + e.getMessage());
            }
        }
        if (xml == null) {
            throw new IllegalStateException("无法获取 NeoForge 版本信息，请检查网络");
        }
        var m = java.util.regex.Pattern.compile("<version>([^<]+)</version>").matcher(xml);
        String best = null;
        while (m.find()) {
            String v = m.group(1);
            if (v.startsWith(base)) {
                if (best == null || compareVersions(v, best) > 0) best = v;
            }
        }
        if (best == null) {
            throw new IllegalStateException("找不到 " + mcVersion + " 对应的 NeoForge 版本");
        }
        return best;
    }

    private static int compareVersions(String a, String b) {
        String[] pa = a.split("[.\\-]");
        String[] pb = b.split("[.\\-]");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            int x = i < pa.length ? parseIntSafe(pa[i]) : 0;
            int y = i < pb.length ? parseIntSafe(pb[i]) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s.replaceAll("\\D.*", "")); }
        catch (Exception e) { return 0; }
    }

    private String findInstalledVersionId(String expected) {
        Path vdir = mcDir.resolve("versions");
        if (Files.isDirectory(vdir.resolve(expected))) return expected;
        // 模糊匹配（安装器可能生成带后缀的目录）
        try (var s = Files.list(vdir)) {
            return s.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith(expected) || expected.startsWith(n))
                    .findFirst().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static String tailOf(Path file, int lines) {
        try {
            List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
            int from = Math.max(0, all.size() - lines);
            return String.join("\n", all.subList(from, all.size()));
        } catch (Exception e) {
            return "";
        }
    }

    // ---------------- 公共 ----------------

    /** 确保某原版版本已安装 */
    private void ensureVanilla(String mcVersion, Consumer<String> stage) throws Exception {
        Path vjson = mcDir.resolve("versions").resolve(mcVersion).resolve(mcVersion + ".json");
        if (Files.isRegularFile(vjson)) return;

        VersionManager vm = new VersionManager(null);
        List<VersionManager.VersionInfo> all = vm.listAllVersions();
        VersionManager.VersionInfo target = all.stream()
                .filter(v -> v.id().equals(mcVersion))
                .findFirst().orElseThrow(() ->
                        new IllegalStateException("Minecraft 版本不存在: " + mcVersion));

        VanillaInstaller installer = new VanillaInstaller(mcDir);
        installer.install(mcVersion, Http.fetchJson(target.url()),
                new VanillaInstaller.Progress() {
                    @Override public void onStage(String s) { stage.accept(s); }
                    @Override public void onProgress(long c, long t) {}
                });
    }
}
