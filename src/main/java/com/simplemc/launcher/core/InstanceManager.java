package com.simplemc.launcher.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simplemc.launcher.util.Constants;
import com.simplemc.launcher.util.Downloader;
import com.simplemc.launcher.util.Http;
import com.simplemc.launcher.util.Log;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 实例管理与整合包导入。
 *
 * <p>每个整合包都创建为独立实例目录（versions/&lt;id&gt;），
 * 通过 inheritsFrom 继承原版，模组配置互不干扰。</p>
 */
public final class InstanceManager {

    private final Path mcDir;

    public InstanceManager(Path mcDir) {
        this.mcDir = mcDir;
    }

    /** 创建一个实例 */
    public String createInstance(String baseVersion, String displayName, String loaderType)
            throws Exception {
        VersionManager vm = new VersionManager(null);
        JsonNode base = vm.readVersionJson(baseVersion);

        String id = makeInstanceId(displayName);
        Path dir = mcDir.resolve("versions").resolve(id);
        Files.createDirectories(dir);

        ObjectNode obj = Http.MAPPER.createObjectNode();
        obj.put("id", id);
        obj.put("inheritsFrom", baseVersion);
        obj.put("type", "release");

        ObjectNode meta = obj.putObject("sclModpack");
        meta.put("name", displayName);
        meta.put("loader", loaderType == null ? "" : loaderType);
        meta.put("createdAt", System.currentTimeMillis());

        obj.set("libraries", Http.MAPPER.createArrayNode());
        obj.set("arguments", Http.MAPPER.createObjectNode());

        Files.writeString(dir.resolve(id + ".json"),
                Http.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(obj),
                StandardCharsets.UTF_8);
        Log.info("创建实例 " + id + "（基于 " + baseVersion + "）");
        return id;
    }

    private String makeInstanceId(String name) {
        String safe = name == null ? "instance"
                : name.replaceAll("[^\\p{L}\\p{N}_\\-]", "_");
        if (safe.length() > 40) safe = safe.substring(0, 40);
        String id = safe + "-" + UUID.randomUUID().toString().substring(0, 6);
        return id;
    }

    /** 实例显示名（没有 sclModpack 就用目录名） */
    public String displayName(String versionId) {
        try {
            VersionManager vm = new VersionManager(null);
            JsonNode j = vm.readVersionJson(versionId);
            String n = j.path("sclModpack").path("name").asText("");
            return n.isBlank() ? versionId : n;
        } catch (Exception e) {
            return versionId;
        }
    }

    /** 解析实例的真实原版版本 */
    public String resolveBaseVersion(String versionId) {
        try {
            VersionManager vm = new VersionManager(null);
            JsonNode j = vm.readVersionJson(versionId);
            String parent = j.path("inheritsFrom").asText("");
            if (parent.isBlank()) return versionId;
            return resolveBaseVersion(parent);
        } catch (Exception e) {
            return VersionManager.stripLoaderSuffix(versionId);
        }
    }

    // ---------------- 整合包导入 ----------------

    /**
     * 导入整合包（.mrpack / .zip）。
     *
     * @return 新建的实例 ID
     */
    public String importModpack(Path archive, java.util.function.Consumer<String> stage)
            throws Exception {
        String name = archive.getFileName().toString().toLowerCase();
        if (name.endsWith(".mrpack")) {
            return importMrpack(archive, stage);
        }
        return importZip(archive, stage);
    }

    /** 导入 Modrinth 整合包 */
    private String importMrpack(Path mrpack, java.util.function.Consumer<String> stage)
            throws Exception {
        stage.accept("读取整合包清单...");
        Path tempDir = Files.createTempDirectory("scl-mrpack-");
        try {
            unzipAll(mrpack, tempDir);

            Path indexFile = tempDir.resolve("modrinth.index.json");
            if (!Files.isRegularFile(indexFile)) {
                throw new IllegalStateException("不是有效的 .mrpack（缺少 modrinth.index.json）");
            }
            JsonNode index = Http.MAPPER.readTree(Files.readAllBytes(indexFile));

            String packName = index.path("name").asText("整合包");
            String mcVersion = index.path("dependencies").path("minecraft").asText("");
            String loaderVersion = "";
            String loaderType = "";
            JsonNode deps = index.path("dependencies");
            if (deps.has("fabric-loader")) {
                loaderType = "fabric";
                loaderVersion = deps.get("fabric-loader").asText();
            } else if (deps.has("quilt-loader")) {
                loaderType = "quilt";
                loaderVersion = deps.get("quilt-loader").asText();
            } else if (deps.has("forge")) {
                loaderType = "forge";
                loaderVersion = deps.get("forge").asText();
            } else if (deps.has("neoforge")) {
                loaderType = "neoforge";
                loaderVersion = deps.get("neoforge").asText();
            }

            if (mcVersion.isBlank()) {
                throw new IllegalStateException("整合包没有指定 Minecraft 版本");
            }
            stage.accept("整合包: " + packName + "（MC " + mcVersion
                    + (loaderType.isBlank() ? "" : " / " + loaderType) + "）");

            // 安装基础版本 + 加载器
            String baseId = installBase(mcVersion, loaderType, loaderVersion, stage);

            // 创建实例
            String instanceId = createInstance(baseId, packName, loaderType);
            Path instanceDir = mcDir.resolve("versions").resolve(instanceId);

            // 下载 files 列表
            JsonNode files = index.path("files");
            List<JsonNode> toDownload = new ArrayList<>();
            if (files.isArray()) {
                for (JsonNode f : files) {
                    String envClient = f.path("env").path("client").asText("required");
                    if ("unsupported".equals(envClient)) continue;
                    toDownload.add(f);
                }
            }

            stage.accept("下载整合包内容（" + toDownload.size() + " 个文件）...");
            downloadPackFiles(toDownload, instanceDir);

            // 复制 overrides
            copyOverrides(tempDir, instanceDir, "overrides", stage);
            copyOverrides(tempDir, instanceDir, "client-overrides", stage);

            // 记录实例元信息
            updateInstanceMeta(instanceId, packName, loaderType, mcVersion);

            stage.accept("整合包导入完成: " + packName);
            return instanceId;
        } finally {
            deleteTree(tempDir);
        }
    }

    private void downloadPackFiles(List<JsonNode> files, Path instanceDir) throws Exception {
        if (files.isEmpty()) return;
        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (JsonNode f : files) {
                String path = f.path("path").asText("");
                JsonNode downloads = f.path("downloads");
                if (path.isBlank() || !downloads.isArray() || downloads.isEmpty()) continue;
                String sha1 = f.path("hashes").path("sha1").asText("");
                List<String> urls = new ArrayList<>();
                for (JsonNode u : downloads) urls.add(u.asText());
                Path dest = instanceDir.resolve(path).normalize();
                if (!dest.startsWith(instanceDir)) continue;   // 防穿越

                futures.add(pool.submit(() -> {
                    try {
                        Downloader.download(urls, dest, null, sha1.isBlank() ? null : sha1);
                    } catch (Exception e) {
                        Log.warn("下载失败 " + path + ": " + e.getMessage());
                    }
                }));
            }
            for (Future<?> f : futures) f.get();
        } finally {
            pool.shutdownNow();
        }
    }

    private void copyOverrides(Path tempDir, Path instanceDir, String sub,
                               java.util.function.Consumer<String> stage) throws Exception {
        Path src = tempDir.resolve(sub);
        if (!Files.isDirectory(src)) return;
        stage.accept("复制 " + sub + " 目录...");
        try (var s = Files.walk(src)) {
            s.forEach(p -> {
                try {
                    Path rel = src.relativize(p);
                    Path dst = instanceDir.resolve(rel);
                    if (Files.isDirectory(p)) {
                        Files.createDirectories(dst);
                    } else {
                        Files.createDirectories(dst.getParent());
                        Files.copy(p, dst, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (Exception ignored) {}
            });
        }
    }

    /** 导入普通 zip（CurseForge 或通用） */
    private String importZip(Path zip, java.util.function.Consumer<String> stage)
            throws Exception {
        stage.accept("分析压缩包结构...");
        Path tempDir = Files.createTempDirectory("scl-zip-");
        try {
            unzipAll(zip, tempDir);

            // CurseForge: manifest.json
            Path cfManifest = tempDir.resolve("manifest.json");
            if (Files.isRegularFile(cfManifest)) {
                return importCurseForge(zip, tempDir, cfManifest, stage);
            }
            // 通用：猜测 MC 版本
            String mcVersion = guessMcVersion(tempDir);
            stage.accept("未找到整合包清单，按通用压缩包处理（MC " + mcVersion + "）");
            String baseId = installBase(mcVersion, "", "", stage);
            String instanceId = createInstance(baseId,
                    zip.getFileName().toString().replaceAll("\\.[^.]+$", ""), "");
            Path instanceDir = mcDir.resolve("versions").resolve(instanceId);

            stage.accept("解压内容到实例目录...");
            try (var s = Files.walk(tempDir)) {
                s.forEach(p -> {
                    try {
                        Path rel = tempDir.relativize(p);
                        if (rel.toString().isEmpty()) return;
                        Path dst = instanceDir.resolve(rel);
                        if (Files.isDirectory(p)) Files.createDirectories(dst);
                        else {
                            Files.createDirectories(dst.getParent());
                            Files.copy(p, dst, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (Exception ignored) {}
                });
            }
            stage.accept("导入完成");
            return instanceId;
        } finally {
            deleteTree(tempDir);
        }
    }

    private String importCurseForge(Path zip, Path tempDir, Path manifestFile,
                                    java.util.function.Consumer<String> stage) throws Exception {
        JsonNode manifest = Http.MAPPER.readTree(Files.readAllBytes(manifestFile));
        String name = manifest.path("name").asText("整合包");
        JsonNode mc = manifest.path("minecraft");
        String mcVersion = mc.path("version").asText("");
        String loaderType = "";
        String loaderVersion = "";
        JsonNode modLoaders = mc.path("modLoaders");
        String primaryId = "";
        if (modLoaders.isArray()) {
            for (JsonNode l : modLoaders) {
                if (l.path("primary").asBoolean(false)) {
                    primaryId = l.path("id").asText("");
                    break;
                }
            }
            if (primaryId.isBlank() && !modLoaders.isEmpty()) {
                primaryId = modLoaders.get(0).path("id").asText("");
            }
        }
        if (primaryId.startsWith("forge-")) {
            loaderType = "forge";
            loaderVersion = primaryId.substring("forge-".length());
        } else if (primaryId.startsWith("fabric-")) {
            loaderType = "fabric";
            loaderVersion = primaryId.substring("fabric-".length());
        } else if (primaryId.startsWith("neoforge-")) {
            loaderType = "neoforge";
            loaderVersion = primaryId.substring("neoforge-".length());
        }

        stage.accept("CurseForge 整合包: " + name + "（MC " + mcVersion + "）");
        String baseId = installBase(mcVersion, loaderType, loaderVersion, stage);
        String instanceId = createInstance(baseId, name, loaderType);
        Path instanceDir = mcDir.resolve("versions").resolve(instanceId);

        // overrides 目录
        copyOverrides(tempDir, instanceDir, "overrides", stage);

        // 记录需要手动下载的模组清单
        JsonNode files = manifest.path("files");
        if (files.isArray() && !files.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("# 该整合包包含 ").append(files.size())
              .append(" 个需要从 CurseForge 下载的文件\n");
            sb.append("# CurseForge 需要 API Key 才能直接下载，请手动获取后放入 mods 目录\n\n");
            for (JsonNode f : files) {
                String projectId = f.path("projectID").asLong() + "";
                String fileId = f.path("fileID").asLong() + "";
                String path = f.path("path").asText("");
                if (path.isBlank()) continue;
                String url = "https://www.curseforge.com/projects/"
                        + projectId + "/files/" + fileId;
                sb.append(path).append("\n  ").append(url).append("\n");
            }
            Files.writeString(instanceDir.resolve("curseforge-downloads.txt"),
                    sb.toString(), StandardCharsets.UTF_8);
            stage.accept("已生成 CurseForge 下载清单（" + files.size() + " 个文件需手动获取）");
        }

        stage.accept("整合包导入完成: " + name);
        return instanceId;
    }

    /** 安装基础版本 + 加载器，返回可用的版本 ID */
    private String installBase(String mcVersion, String loaderType, String loaderVersion,
                               java.util.function.Consumer<String> stage) throws Exception {
        VanillaInstaller vanilla = new VanillaInstaller(mcDir);
        VersionManager vm = new VersionManager(null);
        List<VersionManager.VersionInfo> all = vm.listAllVersions();
        var target = all.stream().filter(v -> v.id().equals(mcVersion)).findFirst()
                .orElseThrow(() -> new IllegalStateException("找不到 MC 版本 " + mcVersion));
        vanilla.install(mcVersion, Http.fetchJson(target.url()),
                new VanillaInstaller.Progress() {
                    @Override public void onStage(String s) { stage.accept(s); }
                    @Override public void onProgress(long c, long t) {}
                });

        if (loaderType == null || loaderType.isBlank()) {
            return mcVersion;
        }

        LoaderInstaller li = new LoaderInstaller(mcDir);
        List<JavaManager.JavaInfo> javas = JavaManager.detectAll();
        JavaManager.JavaInfo java = JavaManager.pickBest(javas,
                JavaManager.requiredJavaMajor(mcVersion));
        String javaBin = java != null ? java.path() : "java";

        return switch (loaderType.toLowerCase()) {
            case "fabric" -> li.installFabricLike(LoaderInstaller.Loader.FABRIC,
                    mcVersion, loaderVersion, stage);
            case "quilt" -> li.installFabricLike(LoaderInstaller.Loader.QUILT,
                    mcVersion, loaderVersion, stage);
            case "forge" -> li.installForgeLike(LoaderInstaller.Loader.FORGE,
                    mcVersion, loaderVersion, javaBin, stage);
            case "neoforge" -> li.installForgeLike(LoaderInstaller.Loader.NEOFORGE,
                    mcVersion, loaderVersion, javaBin, stage);
            default -> mcVersion;
        };
    }

    private void updateInstanceMeta(String instanceId, String name, String loaderType,
                                    String mcVersion) throws Exception {
        Path json = mcDir.resolve("versions").resolve(instanceId).resolve(instanceId + ".json");
        JsonNode j = Http.MAPPER.readTree(Files.readAllBytes(json));
        if (j instanceof ObjectNode obj) {
            ObjectNode meta = obj.withObject("/sclModpack");
            meta.put("name", name);
            meta.put("loader", loaderType == null ? "" : loaderType);
            meta.put("mcVersion", mcVersion);
            Files.writeString(json,
                    Http.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(obj),
                    StandardCharsets.UTF_8);
        }
    }

    /** 从目录结构猜测 MC 版本 */
    private String guessMcVersion(Path dir) {
        // 尝试从 mods 里的 jar 文件名推断
        String latest = "1.21.1";
        try (var s = Files.walk(dir, 4)) {
            var jars = s.filter(p -> p.toString().endsWith(".jar"))
                    .map(p -> p.getFileName().toString()).toList();
            for (String j : jars) {
                var m = java.util.regex.Pattern.compile("1\\.(\\d{1,2})(?:\\.(\\d{1,2}))?")
                        .matcher(j);
                if (m.find()) {
                    return "1." + m.group(1) + (m.group(2) != null ? "." + m.group(2) : "");
                }
            }
        } catch (Exception ignored) {}
        return latest;
    }

    // ---------------- zip 工具 ----------------

    private static void unzipAll(Path zip, Path dest) throws Exception {
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                Path out = dest.resolve(e.getName()).normalize();
                if (!out.startsWith(dest)) continue;   // 防目录穿越
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    try (InputStream in = zf.getInputStream(e)) {
                        Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
    }

    private static void deleteTree(Path dir) {
        try (var s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }
}
