package com.simplemc.launcher.mod;

import com.fasterxml.jackson.databind.JsonNode;
import com.simplemc.launcher.minecraft.ModLoaderManager;
import com.simplemc.launcher.util.AppPaths;
import com.simplemc.launcher.util.Downloader;
import com.simplemc.launcher.util.JsonUtil;
import com.simplemc.launcher.util.Log;
import com.simplemc.launcher.util.VersionUtil;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 模组 / 光影 / 数据包 / 资源包 / 世界 / 整合包管理（基于 Modrinth API）。
 */
public class ModManager {

    public record ModInfo(String name, String file, boolean disabled, long size) {}

    private static final String API = "https://api.modrinth.com/v2";

    private final Path mcDir;
    private final Consumer<String> log;
    private final Consumer<String> status;
    private final BiConsumer<Long, Long> progress;
    private final ModLoaderManager modLoaderManager;

    public ModManager(Path mcDir, Consumer<String> log, Consumer<String> status,
                      BiConsumer<Long, Long> progress, ModLoaderManager modLoaderManager) {
        this.mcDir = mcDir;
        this.log = log;
        this.status = status;
        this.progress = progress;
        this.modLoaderManager = modLoaderManager;
    }

    // ------------------------------------------------------------------
    // 模组
    // ------------------------------------------------------------------
    public boolean installMod(String name, String version) {
        return installProject(name, version, "mod", "mods", "模组");
    }

    // ------------------------------------------------------------------
    // 光影包
    // ------------------------------------------------------------------
    public boolean installShaderpack(String name, String version) {
        return installProject(name, version, "shader", "shaderpacks", "光影包");
    }

    // ------------------------------------------------------------------
    // 数据包
    // ------------------------------------------------------------------
    public boolean installDatapack(String name, String version) {
        return installProject(name, version, "datapack", "datapacks", "数据包");
    }

    // ------------------------------------------------------------------
    // 资源包
    // ------------------------------------------------------------------
    public boolean installResourcepack(String name, String version) {
        return installProject(name, version, "resourcepack", "resourcepacks", "资源包");
    }

    // ------------------------------------------------------------------
    // 世界存档
    // ------------------------------------------------------------------
    public boolean installWorld(String name, String version) {
        return installProject(name, version, "world", "saves", "世界存档");
    }

    /**
     * 通用 Modrinth 项目安装：搜索 -> 选最新版本 -> 下载到目标目录。
     */
    private boolean installProject(String name, String version, String projectType,
                                   String targetSubDir, String label) {
        String current = version == null || version.isBlank() ? "" : version;
        if (current.isBlank()) {
            log.accept("错误: 请先选择/安装一个游戏版本");
            return false;
        }
        String loader = VersionUtil.detectLoader(current);
        String mcVersion = resolveMcVersion(current);
        status.accept("搜索" + label + ": " + name);
        try {
            JsonNode hit = search(name, projectType);
            if (hit == null) {
                log.accept("未找到" + label + ": " + name);
                return false;
            }
            String slug = hit.get("slug").asText();
            log.accept("找到" + label + ": " + hit.get("title").asText());
            JsonNode file = latestFile(slug, mcVersion, "mod".equals(projectType) ? loader : null);
            if (file == null) {
                log.accept(label + "没有适用于 Minecraft " + mcVersion + " 的版本");
                return false;
            }
            String filename = file.get("filename").asText();
            Path dest = mcDir.resolve("versions").resolve(current).resolve(targetSubDir).resolve(filename);
            if (Files.exists(dest)) {
                log.accept(label + "已存在: " + filename);
                return true;
            }
            Files.createDirectories(dest.getParent());
            status.accept("下载 " + filename + "...");
            String sha1 = file.has("hashes") && file.get("hashes").has("sha1")
                    ? file.get("hashes").get("sha1").asText() : null;
            Downloader.download(file.get("url").asText(), dest, sha1, progress);
            log.accept(label + "安装完成: " + filename);
            return true;
        } catch (Exception e) {
            log.accept("安装" + label + "失败: " + e.getMessage());
            Log.error("安装" + label + "失败", e);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 整合包（在线搜索安装）
    // ------------------------------------------------------------------
    public String installModpackFromSearch(String name) throws Exception {
        status.accept("搜索整合包: " + name);
        JsonNode hit = search(name, "modpack");
        if (hit == null) {
            throw new IOException("未找到整合包: " + name);
        }
        String slug = hit.get("slug").asText();
        log.accept("找到整合包: " + hit.get("title").asText());

        JsonNode versions = Downloader.getJson(API + "/project/" + slug + "/version");
        if (versions.size() == 0) {
            throw new IOException("整合包没有可用版本");
        }
        JsonNode latest = versions.get(0);
        JsonNode files = latest.get("files");
        if (files == null || files.size() == 0) {
            throw new IOException("整合包没有可下载文件");
        }
        JsonNode file = files.get(0);
        String filename = file.get("filename").asText();
        String sha1 = file.has("hashes") && file.get("hashes").has("sha1")
                ? file.get("hashes").get("sha1").asText() : null;

        Path tmp = Files.createTempFile("modpack-", ".mrpack");
        try {
            status.accept("下载整合包 " + filename + "...");
            Downloader.download(file.get("url").asText(), tmp, sha1, progress);
            return importModpack(tmp.toString());
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
            }
        }
    }

    // ------------------------------------------------------------------
    // 模组列表 / 启停
    // ------------------------------------------------------------------
    public List<ModInfo> getModsList(String version) {
        List<ModInfo> result = new ArrayList<>();
        Path dir = modsDir(version);
        if (!Files.isDirectory(dir)) {
            return result;
        }
        try (var stream = Files.list(dir)) {
            stream.sorted(Comparator.naturalOrder()).forEach(f -> {
                String name = f.getFileName().toString();
                if (name.endsWith(".jar") || name.endsWith(".disabled")) {
                    boolean disabled = name.endsWith(".disabled");
                    String display = disabled ? name.substring(0, name.length() - 9) : name;
                    long size = 0;
                    try {
                        size = Files.size(f);
                    } catch (IOException ignored) {
                    }
                    result.add(new ModInfo(display, name, disabled, size));
                }
            });
        } catch (IOException e) {
            Log.warn("读取模组列表失败: " + e.getMessage());
        }
        return result;
    }

    public void toggleMod(String name, boolean disable, String version) {
        Path dir = modsDir(version);
        if (!Files.isDirectory(dir)) {
            log.accept("mods 文件夹不存在");
            return;
        }
        try (var stream = Files.list(dir)) {
            List<Path> files = stream.toList();
            Path target = null;
            for (Path f : files) {
                String fn = f.getFileName().toString();
                if (disable && (fn.equals(name) || fn.equals(name + ".jar"))) {
                    target = f;
                    break;
                }
                if (!disable && (fn.equals(name + ".disabled") || fn.equals(name))) {
                    target = f;
                    break;
                }
            }
            if (target == null) {
                log.accept("未找到模组 " + name);
                return;
            }
            Path dst;
            if (disable && !target.getFileName().toString().endsWith(".disabled")) {
                dst = target.resolveSibling(target.getFileName() + ".disabled");
            } else if (!disable && target.getFileName().toString().endsWith(".disabled")) {
                String fn = target.getFileName().toString();
                dst = target.resolveSibling(fn.substring(0, fn.length() - 9));
            } else {
                log.accept("模组状态未改变");
                return;
            }
            Files.move(target, dst, StandardCopyOption.REPLACE_EXISTING);
            log.accept("模组 " + name + " 已" + (disable ? "禁用" : "启用"));
        } catch (Exception e) {
            log.accept("操作失败: " + e.getMessage());
        }
    }

    public void installFabricApi(String mcVersion, String versionFolder) {
        try {
            log.accept("正在自动下载 Fabric API...");
            JsonNode versions = Downloader.getJson(API + "/project/fabric-api/version");
            JsonNode latest = null;
            for (JsonNode v : versions) {
                boolean mcOk = false;
                boolean fabricOk = false;
                if (v.has("game_versions")) {
                    for (JsonNode gv : v.get("game_versions")) {
                        if (gv.asText().equals(mcVersion)) {
                            mcOk = true;
                            break;
                        }
                    }
                }
                if (v.has("loaders")) {
                    for (JsonNode l : v.get("loaders")) {
                        if (l.asText().equals("fabric")) {
                            fabricOk = true;
                            break;
                        }
                    }
                }
                if (mcOk && fabricOk) {
                    latest = v;
                    break;
                }
            }
            if (latest == null || !latest.has("files") || latest.get("files").size() == 0) {
                log.accept("未找到适用于 Minecraft " + mcVersion + " 的 Fabric API");
                return;
            }
            JsonNode file = latest.get("files").get(0);
            String filename = file.get("filename").asText();
            Path dest = mcDir.resolve("versions").resolve(versionFolder).resolve("mods").resolve(filename);
            if (Files.exists(dest)) {
                log.accept("Fabric API 已存在: " + filename);
                return;
            }
            Files.createDirectories(dest.getParent());
            String sha1 = file.has("hashes") && file.get("hashes").has("sha1")
                    ? file.get("hashes").get("sha1").asText() : null;
            status.accept("下载 " + filename + "...");
            Downloader.download(file.get("url").asText(), dest, sha1, progress);
            log.accept("Fabric API 安装完成: " + filename);
        } catch (Exception e) {
            log.accept("自动下载 Fabric API 失败: " + e.getMessage());
        }
    }

    /**
     * 导入 .mrpack 整合包，返回最终的启动版本 id（已统一命名）。
     */
    public String importModpack(String filePath) throws Exception {
        Path pack = Path.of(filePath);
        if (!Files.exists(pack)) {
            throw new IOException("文件不存在: " + filePath);
        }
        JsonNode index;
        try (ZipFile zf = new ZipFile(pack.toFile())) {
            ZipEntry entry = zf.getEntry("modrinth.index.json");
            if (entry == null) {
                throw new IOException("不是有效的 .mrpack 文件（缺少 modrinth.index.json）");
            }
            try (InputStream in = zf.getInputStream(entry)) {
                index = JsonUtil.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        String mcVersion = index.get("dependencies").get("minecraft").asText();
        String loader = detectMrpackLoader(index);
        log.accept("整合包: " + index.get("name").asText() + " (MC " + mcVersion + ")");

        // 安装加载器
        String installedId = mcVersion;
        if (!"vanilla".equals(loader)) {
            status.accept("安装加载器 " + loader + "...");
            // 这里给一个默认的 false，因为整合包导入通常是后台运行且不要求手动取消
            installedId = modLoaderManager.install(loader, mcVersion, new AtomicBoolean(false));
        }

        Path targetDir = mcDir.resolve("versions").resolve(installedId);
        Files.createDirectories(targetDir);

        // 下载模组文件
        if (index.has("files")) {
            status.accept("下载整合包文件...");
            for (JsonNode file : index.get("files")) {
                if (file.has("env") && file.get("env").has("client")
                        && "optional".equals(file.get("env").get("client").asText())) {
                    continue;
                }
                String path = file.get("path").asText();
                String url = file.get("downloads").get(0).asText();
                String sha1 = file.has("hashes") && file.get("hashes").has("sha1")
                        ? file.get("hashes").get("sha1").asText() : null;
                Downloader.download(url, targetDir.resolve(path), sha1, progress);
            }
        }

        // 解压 overrides
        try (ZipFile zf = new ZipFile(pack.toFile())) {
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                String rel = null;
                if (name.startsWith("overrides/")) {
                    rel = name.substring("overrides/".length());
                } else if (name.startsWith("client-overrides/")) {
                    rel = name.substring("client-overrides/".length());
                }
                if (rel == null || rel.isEmpty() || entry.isDirectory()) {
                    continue;
                }
                Path target = targetDir.resolve(rel);
                Files.createDirectories(target.getParent());
                try (InputStream in = zf.getInputStream(entry)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        return installedId;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------
    private JsonNode search(String query, String projectType) throws Exception {
        String facets = "[[\"project_type:" + projectType + "\"]]";
        String url = API + "/search?query=" + enc(query) + "&limit=5&facets=" + enc(facets);
        JsonNode data = Downloader.getJson(url);
        JsonNode hits = data.get("hits");
        return (hits == null || hits.size() == 0) ? null : hits.get(0);
    }

    private JsonNode latestFile(String slug, String mcVersion, String loader) throws Exception {
        String url = API + "/project/" + slug + "/version";
        StringBuilder sb = new StringBuilder(url);
        sb.append("?game_versions=").append(enc(JsonUtil.stringify(java.util.List.of(mcVersion))));
        if (loader != null && !loader.equals("vanilla")) {
            sb.append("&loaders=").append(enc(JsonUtil.stringify(java.util.List.of(loader))));
        }
        JsonNode versions = Downloader.getJson(sb.toString());
        if (versions.size() == 0) {
            return null;
        }
        JsonNode latest = versions.get(0);
        JsonNode files = latest.get("files");
        return (files == null || files.size() == 0) ? null : files.get(0);
    }

    private Path modsDir(String version) {
        if (version == null || version.isBlank()) {
            return mcDir.resolve("mods");
        }
        return mcDir.resolve("versions").resolve(version).resolve("mods");
    }

    private String resolveMcVersion(String version) {
        String loader = VersionUtil.detectLoader(version);
        if (loader.equals("vanilla")) {
            return version;
        }
        if (version.endsWith("-" + loader)) {
            return version.substring(0, version.length() - loader.length() - 1);
        }
        Path json = mcDir.resolve("versions").resolve(version).resolve(version + ".json");
        if (Files.exists(json)) {
            try {
                JsonNode data = JsonUtil.parse(Files.readString(json));
                if (data.has("inheritsFrom")) {
                    return data.get("inheritsFrom").asText();
                }
            } catch (Exception ignored) {
            }
        }
        return version;
    }

    private String detectMrpackLoader(JsonNode index) {
        JsonNode deps = index.get("dependencies");
        if (deps.has("neoforge")) {
            return "neoforge";
        }
        if (deps.has("forge")) {
            return "forge";
        }
        if (deps.has("fabric-loader")) {
            return "fabric";
        }
        if (deps.has("quilt-loader")) {
            return "quilt";
        }
        return "vanilla";
    }

    private String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}