package com.simplemc.launcher.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simplemc.launcher.util.Constants;
import com.simplemc.launcher.util.Downloader;
import com.simplemc.launcher.util.Http;
import com.simplemc.launcher.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 原版 Minecraft 安装器：下载版本 JSON、客户端 jar、libraries、assets。
 */
public final class VanillaInstaller {

    /** 统一进度回调 */
    public interface Progress {
        /** @param stage 当前阶段说明 */
        void onStage(String stage);
        /** @param current 已完成文件数 */
        void onProgress(long current, long total);
    }

    private static final int PARALLEL = 8;
    /**
     * 资源文件并发数。
     * 官方 CDN 对中等并发容忍度不错，8 并发在保证速度的同时不易被限流。
     */
    private static final int PARALLEL_ASSETS = 8;

    private final Path mcDir;

    public VanillaInstaller(Path mcDir) {
        this.mcDir = mcDir;
    }

    /**
     * 安装指定版本。
     *
     * @param versionId 如 "1.21.1"
     * @param progress  进度回调（可为 null）
     * @return 安装后的版本 JSON
     */
    public JsonNode install(String versionId, JsonNode versionEntryJson, Progress progress)
            throws Exception {
        report(progress).onStage("获取版本信息 " + versionId);

        // 1. 拿到版本 JSON（如果没给就自己去拉）
        JsonNode vjson = versionEntryJson;
        if (vjson == null || !vjson.has("libraries")) {
            String url = fetchVersionUrl(versionId);
            vjson = Http.fetchJson(url);
        }

        // 2. 写版本 JSON
        Path versionDir = mcDir.resolve("versions").resolve(versionId);
        Files.createDirectories(versionDir);
        Path versionJsonFile = versionDir.resolve(versionId + ".json");
        Files.writeString(versionJsonFile,
                Http.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(vjson),
                StandardCharsets.UTF_8);

        // 3. 下载客户端 jar
        JsonNode client = vjson.path("downloads").path("client");
        if (client.isMissingNode()) {
            throw new IllegalStateException("版本 " + versionId + " 没有客户端下载信息");
        }
        String clientUrl = client.path("url").asText();
        String clientSha1 = client.path("sha1").asText("");
        Path clientJar = versionDir.resolve(versionId + ".jar");
        report(progress).onStage("下载客户端 " + versionId);
        if (!Files.isRegularFile(clientJar) || !Downloader.sha1(clientJar).equalsIgnoreCase(clientSha1)) {
            Downloader.download(preferMirror(clientUrl), clientJar, null, clientSha1);
        }

        // 4. 下载 libraries
        JsonNode libs = vjson.path("libraries");
        List<DownloadTask> tasks = new ArrayList<>();
        if (libs.isArray()) {
            for (JsonNode lib : libs) {
                collectLibraryDownloads(lib, tasks);
            }
        }
        report(progress).onStage("下载依赖库 (" + tasks.size() + " 个)");
        downloadAll(tasks, progress);

        // 5. 下载 assets
        JsonNode assetIndex = vjson.path("assetIndex");
        if (!assetIndex.isMissingNode()) {
            String indexUrl = assetIndex.path("url").asText();
            String indexId = assetIndex.path("id").asText();
            String indexSha1 = assetIndex.path("sha1").asText("");
            Path indexPath = mcDir.resolve("assets").resolve("indexes").resolve(indexId + ".json");
            Files.createDirectories(indexPath.getParent());

            report(progress).onStage("获取资源索引");
            if (!Files.isRegularFile(indexPath)
                    || (!indexSha1.isEmpty()
                        && !Downloader.sha1(indexPath).equalsIgnoreCase(indexSha1))) {
                // assetIndex 的 URL 形如 /v1/packages/<sha1>/<id>.json，
                // BMCLAPI 镜像这个路径时可能返回错误内容导致 SHA1 不符，
                // 所以镜像失败后必须回退官方源重试。
                List<String> urls = preferMirror(indexUrl);
                boolean got = false;
                Exception last = null;
                for (String u : urls) {
                    try {
                        Downloader.download(List.of(u), indexPath, null, indexSha1);
                        got = true;
                        break;
                    } catch (Exception e) {
                        last = e;
                        Log.warn("资源索引下载失败 " + shorten(u) + ": "
                                + Downloader.describe(e));
                    }
                }
                if (!got) {
                    // 最后兜底：不校验 SHA1 再试一次（有些镜像内容一致但 hash 字段不规范）
                    for (String u : urls) {
                        try {
                            Downloader.download(List.of(u), indexPath, null, null);
                            got = true;
                            break;
                        } catch (Exception e) {
                            last = e;
                        }
                    }
                }
                if (!got) {
                    throw new IOException("无法获取资源索引: "
                            + (last == null ? "未知错误" : Downloader.describe(last)));
                }
                Log.info("资源索引已就绪: " + indexPath.getFileName());
            }

            // 下载资源对象
            List<DownloadTask> assetTasks = collectAssetDownloads(indexPath);
            if (!assetTasks.isEmpty()) {
                report(progress).onStage("下载资源文件 (" + assetTasks.size() + " 个)");
                downloadAll(assetTasks, progress, PARALLEL_ASSETS);
            }
        }

        report(progress).onStage("安装完成 " + versionId);
        Log.info("版本 " + versionId + " 安装完成");
        return vjson;
    }

    /**
     * 只下载某个版本 JSON 里声明的 libraries（不含客户端 jar 与资源）。
     * 用于加载器安装后补齐依赖。
     */
    public void installLibrariesOnly(JsonNode vjson, Progress progress) throws Exception {
        List<DownloadTask> tasks = new ArrayList<>();
        JsonNode libs = vjson.path("libraries");
        if (libs.isArray()) {
            for (JsonNode lib : libs) {
                collectLibraryDownloads(lib, tasks);
            }
        }
        if (tasks.isEmpty()) {
            report(progress).onStage("依赖库已完整");
            return;
        }
        report(progress).onStage("下载 " + tasks.size() + " 个依赖库");
        downloadAll(tasks, progress);
    }

    private String fetchVersionUrl(String versionId) throws Exception {
        VersionManager vm = new VersionManager(null);
        String url = vm.versionUrl(versionId);
        if (url == null) {
            throw new IllegalStateException("找不到版本 " + versionId);
        }
        return url;
    }

    /** 把官方 URL 转成 BMCLAPI 镜像地址，下载更快 */
    public static List<String> preferMirror(String originalUrl) {
        List<String> out = new ArrayList<>();
        if (originalUrl == null) return out;
        String mirrored = originalUrl;
        if (originalUrl.startsWith("https://piston-meta.mojang.com")) {
            mirrored = originalUrl.replace("https://piston-meta.mojang.com",
                    VersionManager.BMCLAPI_BASE);
        } else if (originalUrl.startsWith("https://launcher.mojang.com")) {
            mirrored = originalUrl.replace("https://launcher.mojang.com",
                    VersionManager.BMCLAPI_BASE + "/launcher");
        } else if (originalUrl.startsWith("https://resources.download.minecraft.net")) {
            mirrored = originalUrl.replace("https://resources.download.minecraft.net",
                    VersionManager.BMCLAPI_BASE + "/assets");
        } else if (originalUrl.startsWith("https://libraries.minecraft.net")) {
            mirrored = originalUrl.replace("https://libraries.minecraft.net",
                    VersionManager.BMCLAPI_BASE + "/maven");
        }
        if (!mirrored.equals(originalUrl)) out.add(mirrored);
        out.add(originalUrl);
        return out;
    }

    /** 一条下载任务 */
    private record DownloadTask(List<String> urls, Path dest, String sha1, long size) {}

    /** 收集一个 library 里需要下载的文件 */
    private void collectLibraryDownloads(JsonNode lib, List<DownloadTask> tasks) {
        if (!ruleAllows(lib)) return;

        JsonNode downloads = lib.path("downloads");
        JsonNode artifact = downloads.path("artifact");
        if (!artifact.isMissingNode()) {
            String path = artifact.path("path").asText();
            String url = artifact.path("url").asText();
            String sha1 = artifact.path("sha1").asText("");
            long size = artifact.path("size").asLong(0);
            if (!path.isBlank() && !url.isBlank()) {
                Path dest = mcDir.resolve("libraries").resolve(path);
                if (!alreadyOk(dest, sha1)) {
                    tasks.add(new DownloadTask(preferMirror(url), dest, sha1, size));
                }
            }
        } else {
            // 没有 downloads.artifact 时，用 name 的 maven 坐标 + url 仓库地址
            String name = lib.path("name").asText("");
            String repo = lib.path("url").asText("");
            String sha1 = lib.path("sha1").asText("");
            if (!name.isBlank()) {
                Path dest = mavenToPath(name);
                if (dest != null && !alreadyOk(dest, sha1)) {
                    String base = repo.isBlank() ? "https://libraries.minecraft.net/" : repo;
                    if (!base.endsWith("/")) base = base + "/";
                    String url = base + mavenRelative(name);
                    List<String> urls = new ArrayList<>();
                    urls.add(url);
                    // 官方库再加一个 MC 库镜像兜底
                    if (repo.isBlank() || repo.contains("minecraft")) {
                        urls.add(VersionManager.BMCLAPI_BASE + "/maven/" + mavenRelative(name));
                    }
                    tasks.add(new DownloadTask(urls, dest, sha1, 0));
                    Log.info("[lib] 待下载 " + name + " -> " + dest.getFileName());
                } else if (dest != null) {
                    Log.info("[lib] 已存在 " + name);
                }
            } else {
                Log.warn("[lib] 无 name 且无 artifact: " + lib.path("url").asText(""));
            }
        }

        // 带 classifiers 的（常见于 natives）
        JsonNode classifiers = downloads.path("classifiers");
        if (classifiers.isObject()) {
            String nativesKey = nativesClassifierKey(lib);
            var it = classifiers.fields();
            while (it.hasNext()) {
                var e = it.next();
                String key = e.getKey();
                if (nativesKey != null && !key.equals(nativesKey)) continue;
                JsonNode c = e.getValue();
                String path = c.path("path").asText();
                String url = c.path("url").asText();
                String sha1 = c.path("sha1").asText("");
                if (!path.isBlank() && !url.isBlank()) {
                    Path dest = mcDir.resolve("libraries").resolve(path);
                    if (!alreadyOk(dest, sha1)) {
                        tasks.add(new DownloadTask(preferMirror(url), dest, sha1, 0));
                    }
                }
            }
        }
    }

    /** maven 坐标 -> libraries 下的路径 */
    private Path mavenToPath(String coord) {
        try {
            String[] p = coord.split(":");
            if (p.length < 3) return null;
            String group = p[0], artifact = p[1], version = p[2];
            String classifier = p.length > 3 ? "-" + p[3] : "";
            Path path = mcDir.resolve("libraries");
            for (String g : group.split("\\.")) path = path.resolve(g);
            return path.resolve(artifact).resolve(version)
                    .resolve(artifact + "-" + version + classifier + ".jar");
        } catch (Exception e) {
            return null;
        }
    }

    /** maven 坐标 -> 仓库相对路径 */
    private static String mavenRelative(String coord) {
        String[] p = coord.split(":");
        if (p.length < 3) return "";
        String group = p[0].replace('.', '/'), artifact = p[1], version = p[2];
        String classifier = p.length > 3 ? "-" + p[3] : "";
        return group + "/" + artifact + "/" + version + "/"
                + artifact + "-" + version + classifier + ".jar";
    }

    /** 计算当前平台需要的 natives classifier key */
    private String nativesClassifierKey(JsonNode lib) {
        JsonNode natives = lib.path("natives");
        if (natives.isMissingNode()) return null;
        String os = currentOsName();
        JsonNode n = natives.get(os);
        if (n == null) return null;
        return n.asText().replace("${arch}", is64Bit() ? "64" : "32");
    }

    private static String currentOsName() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) return "windows";
        if (os.contains("mac")) return "osx";
        return "linux";
    }

    private static boolean is64Bit() {
        return System.getProperty("os.arch", "").contains("64");
    }

    /** 规则判断（只处理 os 规则） */
    private boolean ruleAllows(JsonNode lib) {
        JsonNode rules = lib.path("rules");
        if (!rules.isArray() || rules.isEmpty()) return true;
        boolean allowed = false;
        for (JsonNode rule : rules) {
            String action = rule.path("action").asText();
            JsonNode osNode = rule.path("os");
            boolean matches = true;
            if (!osNode.isMissingNode()) {
                String name = osNode.path("name").asText("");
                if (!name.isBlank() && !name.equals(currentOsName())) {
                    matches = false;
                }
                String arch = osNode.path("arch").asText("");
                if (!arch.isBlank()) {
                    String sysArch = System.getProperty("os.arch", "").toLowerCase();
                    if (arch.equals("x86") && sysArch.contains("64")) matches = false;
                    if (arch.equals("arm64") && !sysArch.contains("aarch64")) matches = false;
                }
            }
            if (matches) {
                allowed = "allow".equals(action);
            }
        }
        return allowed;
    }

    /** 资源索引 → 下载任务 */
    private List<DownloadTask> collectAssetDownloads(Path indexPath) throws IOException {
        List<DownloadTask> tasks = new ArrayList<>();
        JsonNode index = Http.MAPPER.readTree(Files.readAllBytes(indexPath));
        JsonNode objects = index.path("objects");
        if (!objects.isObject()) return tasks;

        Path objectsDir = mcDir.resolve("assets").resolve("objects");
        Files.createDirectories(objectsDir);

        AtomicLong totalSize = new AtomicLong();
        var it = objects.fields();
        while (it.hasNext()) {
            var e = it.next();
            JsonNode obj = e.getValue();
            String hash = obj.path("hash").asText();
            long size = obj.path("size").asLong(0);
            if (hash.length() < 2) continue;
            String sub = hash.substring(0, 2);
            Path dest = objectsDir.resolve(sub).resolve(hash);
            if (Files.isRegularFile(dest) && Files.size(dest) == size) continue;
            // 官方 CDN 优先：BMCLAPI 对高频资源请求会大量返回 429，
            // 反而比官方源慢得多；官方源失败后再回退 BMCLAPI。
            String official = "https://resources.download.minecraft.net/" + sub + "/" + hash;
            String mirror = VersionManager.BMCLAPI_BASE + "/assets/" + sub + "/" + hash;
            tasks.add(new DownloadTask(List.of(official, mirror), dest, hash, size));
            totalSize.addAndGet(size);
        }
        Log.info("资源文件待下载 " + tasks.size() + " 个，共 "
                + Downloader.humanSize(totalSize.get()));
        return tasks;
    }

    /** 并发下载一批任务 */
    private void downloadAll(List<DownloadTask> tasks, Progress progress) throws Exception {
        downloadAll(tasks, progress, PARALLEL);
    }

    private void downloadAll(List<DownloadTask> tasks, Progress progress, int parallel)
            throws Exception {
        if (tasks.isEmpty()) return;
        AtomicInteger doneCount = new AtomicInteger();
        AtomicLong doneBytes = new AtomicLong();
        long grandTotal = tasks.stream().mapToLong(DownloadTask::size).sum();
        if (grandTotal <= 0) grandTotal = tasks.size();

        ExecutorService pool = Executors.newFixedThreadPool(parallel);
        List<Future<?>> futures = new ArrayList<>();
        final long total = grandTotal;
        try {
            for (DownloadTask t : tasks) {
                futures.add(pool.submit(() -> {
                    // 所有网络错误都重试（TLS 握手失败 / 超时 / 429 限流 等），最多 4 次
                    boolean ok = false;
                    for (int attempt = 0; attempt < 4 && !ok; attempt++) {
                        try {
                            Downloader.download(t.urls(), t.dest(), null, t.sha1());
                            ok = true;
                        } catch (Exception e) {
                            String msg = Downloader.describe(e);
                            boolean retryable = msg.contains("429")
                                    || msg.contains("handshake")
                                    || msg.contains("timed out")
                                    || msg.contains("timeout")
                                    || msg.contains("Connection reset")
                                    || msg.contains("SSL")
                                    || msg.contains("EOF")
                                    || msg.contains("closed");
                            if (attempt == 3 || !retryable) {
                                Log.warn("下载失败 " + t.dest().getFileName()
                                        + ": " + msg);
                                break;
                            }
                            try {
                                // 退避：1s / 2s / 4s
                                Thread.sleep(1000L * (1L << attempt));
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }
                    int d = doneCount.incrementAndGet();
                    doneBytes.addAndGet(Math.max(t.size(), 1));
                    if (progress != null) {
                        progress.onProgress(
                                total == tasks.size() ? d : doneBytes.get(),
                                total);
                    }
                }));
            }
            for (Future<?> f : futures) f.get();
        } finally {
            pool.shutdownNow();
        }
    }

    private static String shorten(String url) {
        if (url == null) return "";
        return url.length() > 90 ? url.substring(0, 90) + "..." : url;
    }

    private static boolean alreadyOk(Path dest, String sha1) {
        if (!Files.isRegularFile(dest)) return false;
        if (sha1 == null || sha1.isBlank()) return Files.exists(dest);
        return Downloader.sha1(dest).equalsIgnoreCase(sha1);
    }

    /** 解压 natives（老版本需要） */
    public static void extractNatives(JsonNode vjson, Path nativesDir) throws IOException {
        Files.createDirectories(nativesDir);
        JsonNode libs = vjson.path("libraries");
        if (!libs.isArray()) return;
        String os = currentOsName();
        for (JsonNode lib : libs) {
            JsonNode natives = lib.path("natives");
            if (natives.isMissingNode()) continue;
            JsonNode n = natives.get(os);
            if (n == null) continue;
            String key = n.asText().replace("${arch}", is64Bit() ? "64" : "32");
            JsonNode cls = lib.path("downloads").path("classifiers").get(key);
            if (cls == null) continue;
            String path = cls.path("path").asText();
            if (path.isBlank()) continue;
            Path jar = Constants.MC_DIR.resolve("libraries").resolve(path);
            if (!Files.isRegularFile(jar)) continue;
            try (InputStream in = Files.newInputStream(jar);
                 ZipInputStream zis = new ZipInputStream(in)) {
                ZipEntry e;
                while ((e = zis.getNextEntry()) != null) {
                    if (e.isDirectory()) continue;
                    String name = e.getName();
                    if (name.contains("..")) continue;
                    if (!name.endsWith(".dll") && !name.endsWith(".so")
                            && !name.endsWith(".dylib") && !name.endsWith(".jnilib")) {
                        continue;
                    }
                    Path out = nativesDir.resolve(Path.of(name).getFileName().toString());
                    Files.copy(zis, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception e) {
                Log.warn("解压 natives 失败 " + jar.getFileName() + ": " + e.getMessage());
            }
        }
    }

    private static Progress report(Progress p) {
        return p != null ? p : NOOP;
    }

    private static final Progress NOOP = new Progress() {
        @Override public void onStage(String stage) { Log.info(stage); }
        @Override public void onProgress(long current, long total) {}
    };

    private static final Map<String, ObjectNode> EMPTY = new LinkedHashMap<>();
}
