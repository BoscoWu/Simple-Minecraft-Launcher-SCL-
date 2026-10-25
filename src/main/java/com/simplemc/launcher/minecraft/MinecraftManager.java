package com.simplemc.launcher.minecraft;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simplemc.launcher.util.AppPaths;
import com.simplemc.launcher.util.Downloader;
import com.simplemc.launcher.util.JsonUtil;
import com.simplemc.launcher.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Minecraft 版本下载、启动、以及库/资源/运行时的处理。
 */
public class MinecraftManager {

    private final Path mcDir;
    private final Consumer<String> log;
    private final Consumer<String> status;
    private final BiConsumer<Long, Long> progress;

    public MinecraftManager(Path mcDir, Consumer<String> log, Consumer<String> status,
                            BiConsumer<Long, Long> progress) {
        this.mcDir = mcDir;
        this.log = log;
        this.status = status;
        this.progress = progress;
    }

    // ------------------------------------------------------------------
    // 版本下载（全并发优化版）
    // ------------------------------------------------------------------
    public void installVanilla(String version, AtomicBoolean cancelFlag) throws Exception {
        status.accept("正在下载 Minecraft " + version + "...");
        JsonNode manifest = Downloader.getJson(
                "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");
        String versionUrl = null;
        String versionSha1 = null;
        for (JsonNode v : manifest.get("versions")) {
            if (v.get("id").asText().equals(version)) {
                versionUrl = v.get("url").asText();
                versionSha1 = v.get("sha1").asText();
                break;
            }
        }
        if (versionUrl == null) {
            throw new IOException("版本不存在: " + version);
        }

        Path versionDir = mcDir.resolve("versions").resolve(version);
        Path versionJson = versionDir.resolve(version + ".json");
        Downloader.download(versionUrl, versionJson, versionSha1, progress, cancelFlag);
        if (cancelFlag.get()) return;

        JsonNode data = JsonUtil.parse(Files.readString(versionJson));

        // 计算最大并发数（根据 CPU 核心数）
        int maxThreads = Math.max(4, Math.min(Runtime.getRuntime().availableProcessors() * 4, 64));
        ExecutorService pool = Executors.newFixedThreadPool(maxThreads);
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        // 1. 客户端 Jar 下载加入并发池
        if (data.has("downloads") && data.get("downloads").has("client")) {
            JsonNode client = data.get("downloads").get("client");
            futures.add(CompletableFuture.runAsync(() -> {
                if (cancelFlag.get()) return;
                try {
                    Downloader.download(client.get("url").asText(),
                            versionDir.resolve(version + ".jar"), client.get("sha1").asText(), progress, cancelFlag);
                } catch (Exception e) {
                    Log.warn("客户端 Jar 下载失败: " + e.getMessage());
                }
            }, pool));
        }

        // 2. 库文件并发
        if (data.has("libraries")) {
            Path nativesDir = versionDir.resolve("natives");
            for (JsonNode lib : data.get("libraries")) {
                if (!libraryApplies(lib)) continue;

                futures.add(CompletableFuture.runAsync(() -> {
                    if (cancelFlag.get()) return;
                    try {
                        String name = lib.get("name").asText();
                        Path jarPath = libraryPath(name);
                        String url = null;
                        String sha1 = null;

                        if (lib.has("downloads") && lib.get("downloads").has("artifact")
                                && lib.get("downloads").get("artifact").has("url")) {
                            JsonNode art = lib.get("downloads").get("artifact");
                            url = art.get("url").asText();
                            sha1 = art.has("sha1") ? art.get("sha1").asText() : null;
                            jarPath = mcDir.resolve("libraries").resolve(art.get("path").asText());
                        } else {
                            url = mavenUrl(name, lib);
                            if (url == null) return;
                        }

                        Downloader.download(url, jarPath, sha1, progress, cancelFlag);

                        // 处理原生库
                        if (lib.has("natives")) {
                            String nativeKey = nativesKey(lib);
                            if (nativeKey != null) {
                                String nativeUrl = null;
                                String nativeSha1 = null;
                                Path nativeJar = nativeLibraryPath(name, nativeKey);
                                if (lib.has("downloads") && lib.get("downloads").has("classifiers")
                                        && lib.get("downloads").get("classifiers").has(nativeKey)) {
                                    JsonNode cl = lib.get("downloads").get("classifiers").get(nativeKey);
                                    nativeUrl = cl.get("url").asText();
                                    nativeSha1 = cl.has("sha1") ? cl.get("sha1").asText() : null;
                                } else if (url != null) {
                                    nativeUrl = url.replaceFirst("\\.jar$", "-" + nativeKey + ".jar");
                                }
                                if (nativeUrl != null) {
                                    Downloader.download(nativeUrl, nativeJar, nativeSha1, progress, cancelFlag);
                                    extractZip(nativeJar, nativesDir, lib);
                                }
                            }
                        }
                    } catch (InterruptedException ignored) {
                    } catch (Exception e) {
                        Log.warn("库下载失败: " + e.getMessage());
                    }
                }, pool));
            }
        }

        // 3. 资源文件并发
        if (data.has("assetIndex")) {
            JsonNode index = data.get("assetIndex");
            String assetsId = data.has("assets") ? data.get("assets").asText() : index.get("id").asText();
            Path indexFile = mcDir.resolve("assets/indexes").resolve(assetsId + ".json");
            
            try {
                Downloader.download(index.get("url").asText(), indexFile,
                        index.has("sha1") ? index.get("sha1").asText() : null, progress, cancelFlag);
            } catch (Exception e) {
                Log.warn("获取资源索引失败: " + e.getMessage());
            }

            if (Files.exists(indexFile) && !cancelFlag.get()) {
                JsonNode indexData = JsonUtil.parse(Files.readString(indexFile));
                if (indexData.has("objects")) {
                    JsonNode objects = indexData.get("objects");
                    objects.fields().forEachRemaining(e -> {
                        futures.add(CompletableFuture.runAsync(() -> {
                            if (cancelFlag.get()) return;
                            try {
                                String hash = e.getValue().get("hash").asText();
                                String url = "https://resources.download.minecraft.net/" + hash.substring(0, 2) + "/" + hash;
                                Path dest = mcDir.resolve("assets/objects").resolve(hash.substring(0, 2)).resolve(hash);
                                Downloader.download(url, dest, hash, progress, cancelFlag);
                            } catch (InterruptedException ignored) {
                            } catch (Exception ex) {
                                Log.warn("资源下载失败 " + e.getKey() + ": " + ex.getMessage());
                            }
                        }, pool));
                    });
                }
            }
        }

        // 4. 日志配置
        if (data.has("logging") && data.get("logging").has("client")) {
            JsonNode lc = data.get("logging").get("client").get("file");
            futures.add(CompletableFuture.runAsync(() -> {
                if (cancelFlag.get()) return;
                try {
                    Downloader.download(lc.get("url").asText(),
                            mcDir.resolve("assets/log_configs").resolve(lc.get("id").asText()),
                            lc.get("sha1").asText(), progress, cancelFlag);
                } catch (Exception e) {
                    Log.warn("日志配置下载失败: " + e.getMessage());
                }
            }, pool));
        }

        // 等待所有下载完成
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        pool.shutdown();

        if (cancelFlag.get()) {
            throw new InterruptedException("下载已取消");
        }
    }

    // ------------------------------------------------------------------
    // 启动与工具方法 (不变)
    // ------------------------------------------------------------------
    public Process launch(String version, String username, String uuid, String token,
                          String maxMem, String minMem) throws Exception {
        Path versionDir = mcDir.resolve("versions").resolve(version);
        Files.createDirectories(versionDir);
        setGameLanguageToChinese(versionDir);
        JsonNode data = readVersionJson(version);

        Map<String, String> vars = new HashMap<>();
        vars.put("auth_player_name", username);
        vars.put("auth_uuid", uuid);
        vars.put("auth_access_token", token);
        vars.put("auth_session", token);
        vars.put("version_name", version);
        vars.put("game_directory", versionDir.toString());
        vars.put("assets_root", mcDir.resolve("assets").toString());
        vars.put("assets_index_name", data.has("assets") ? data.get("assets").asText() : version);
        vars.put("game_assets", mcDir.resolve("assets/virtual/legacy").toString());
        vars.put("user_type", "msa");
        vars.put("version_type", data.has("type") ? data.get("type").asText() : "release");
        vars.put("user_properties", "{}");
        vars.put("launcher_name", "SimpleMCLauncher");
        vars.put("launcher_version", AppPaths.VERSION);

        String baseVersion = data.has("inheritsFrom") ? data.get("inheritsFrom").asText() : version;
        Path nativesDir = mcDir.resolve("versions").resolve(baseVersion).resolve("natives");
        vars.put("natives_directory", nativesDir.toString());
        vars.put("library_directory", mcDir.resolve("libraries").toString());
        vars.put("classpath_separator", java.io.File.pathSeparator);
        vars.put("clientid", "");
        vars.put("auth_xuid", "");

        String classpath = buildClasspath(data, version);
        vars.put("classpath", classpath);

        List<String> cmd = new ArrayList<>();
        cmd.add(findJavaExecutable());
        maxMem = sanitizeMem(maxMem, "2G");
        minMem = sanitizeMem(minMem, "1G");
        cmd.add("-Xmx" + maxMem);
        cmd.add("-Xms" + minMem);
        cmd.add("-XX:+UseG1GC");
        cmd.add("-Dfile.encoding=UTF-8");

        if (data.has("arguments") && data.get("arguments").has("jvm")) {
            cmd.addAll(parseArgs(data.get("arguments").get("jvm"), vars));
        } else {
            cmd.add("-Djava.library.path=" + nativesDir.toString());
            cmd.add("-cp");
            cmd.add(classpath);
        }
        cmd.add(data.get("mainClass").asText());

        if (data.has("minecraftArguments")) {
            cmd.addAll(parseStringArgs(data.get("minecraftArguments").asText(), vars));
        } else if (data.has("arguments") && data.get("arguments").has("game")) {
            cmd.addAll(parseArgs(data.get("arguments").get("game"), vars));
        }

        Log.info("启动命令: " + String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(versionDir.toFile());
        pb.redirectErrorStream(true);
        return pb.start();
    }

    public List<String> installedVersions() {
        List<String> result = new ArrayList<>();
        Path versionsDir = mcDir.resolve("versions");
        if (!Files.isDirectory(versionsDir)) {
            return result;
        }
        try (var stream = Files.list(versionsDir)) {
            stream.filter(Files::isDirectory).forEach(d -> {
                String id = d.getFileName().toString();
                if (Files.exists(d.resolve(id + ".json"))) {
                    result.add(id);
                }
            });
        } catch (IOException e) {
            Log.warn("读取版本列表失败: " + e.getMessage());
        }
        result.sort(Comparator.naturalOrder());
        return result;
    }

    public boolean renameVersion(String oldId, String newId) {
        if (oldId.equals(newId)) {
            return true;
        }
        Path oldDir = mcDir.resolve("versions").resolve(oldId);
        Path newDir = mcDir.resolve("versions").resolve(newId);
        if (!Files.isDirectory(oldDir) || Files.isDirectory(newDir)) {
            return false;
        }
        try {
            Path oldJson = oldDir.resolve(oldId + ".json");
            Path newJson = oldDir.resolve(newId + ".json");
            if (Files.exists(oldJson)) {
                JsonNode data = JsonUtil.parse(Files.readString(oldJson));
                if (data.isObject()) {
                    ((ObjectNode) data).put("id", newId);
                }
                Files.writeString(newJson, JsonUtil.stringifyPretty(data));
                Files.deleteIfExists(oldJson);
            }
            Path oldJar = oldDir.resolve(oldId + ".jar");
            Path newJar = oldDir.resolve(newId + ".jar");
            if (Files.exists(oldJar) && !Files.exists(newJar)) {
                Files.move(oldJar, newJar);
            }
            Files.move(oldDir, newDir);
            return true;
        } catch (Exception e) {
            Log.warn("重命名版本目录失败 " + oldId + " -> " + newId + ": " + e.getMessage());
            return false;
        }
    }

    public String findJavaExecutable() {
        Path javaDir = AppPaths.JAVA_DIR;
        if (Files.isDirectory(javaDir)) {
            try (var stream = Files.list(javaDir)) {
                List<Path> dirs = stream.filter(Files::isDirectory)
                        .sorted(Comparator.reverseOrder()).toList();
                for (Path d : dirs) {
                    Path exe = d.resolve("bin/javaw.exe");
                    if (Files.exists(exe)) {
                        return exe.toString();
                    }
                    exe = d.resolve("bin/java.exe");
                    if (Files.exists(exe)) {
                        return exe.toString();
                    }
                    exe = d.resolve("bin/java");
                    if (Files.exists(exe)) {
                        return exe.toString();
                    }
                }
            } catch (IOException ignored) {
            }
        }
        String javaHome = System.getProperty("java.home");
        Path exe = Path.of(javaHome, "bin", isWindows() ? "java.exe" : "java");
        if (Files.exists(exe)) {
            return exe.toString();
        }
        return isWindows() ? "javaw" : "java";
    }

    // 内部工具... (保持原样)
    private JsonNode readVersionJson(String version) throws Exception {
        Path json = mcDir.resolve("versions").resolve(version).resolve(version + ".json");
        JsonNode data = JsonUtil.parse(Files.readString(json));
        if (data.has("inheritsFrom")) {
            String base = data.get("inheritsFrom").asText();
            JsonNode baseData = JsonUtil.parse(Files.readString(
                    mcDir.resolve("versions").resolve(base).resolve(base + ".json")));
            data = mergeJson(baseData, data);
        }
        return data;
    }

    private JsonNode mergeJson(JsonNode base, JsonNode child) {
        ObjectNode merged = base.deepCopy();
        List<JsonNode> libs = new ArrayList<>();
        Map<String, Boolean> seen = new HashMap<>();
        if (child.has("libraries")) {
            for (JsonNode lib : child.get("libraries")) {
                libs.add(lib);
                seen.put(libName(lib.get("name").asText()), true);
            }
        }
        if (base.has("libraries")) {
            for (JsonNode lib : base.get("libraries")) {
                if (!seen.containsKey(libName(lib.get("name").asText()))) {
                    libs.add(lib);
                }
            }
        }
        merged.set("libraries", JsonUtil.MAPPER.valueToTree(libs));

        child.fields().forEachRemaining(e -> {
            String key = e.getKey();
            JsonNode childVal = e.getValue();
            if ("libraries".equals(key)) {
                return;
            }
            JsonNode baseVal = merged.get(key);
            if (childVal.isArray() && baseVal != null && baseVal.isArray()) {
                var arr = JsonUtil.newArray();
                baseVal.forEach(arr::add);
                childVal.forEach(arr::add);
                merged.set(key, arr);
            } else if (childVal.isObject() && baseVal != null && baseVal.isObject()) {
                ObjectNode obj = ((ObjectNode) baseVal).deepCopy();
                childVal.fields().forEachRemaining(ce -> {
                    JsonNode cv = ce.getValue();
                    if (cv.isArray() && obj.has(ce.getKey()) && obj.get(ce.getKey()).isArray()) {
                        var arr = JsonUtil.newArray();
                        obj.get(ce.getKey()).forEach(arr::add);
                        cv.forEach(arr::add);
                        obj.set(ce.getKey(), arr);
                    } else {
                        obj.set(ce.getKey(), cv);
                    }
                });
                merged.set(key, obj);
            } else {
                merged.set(key, childVal);
            }
        });
        return merged;
    }

    private String buildClasspath(JsonNode data, String version) {
        List<String> parts = new ArrayList<>();
        if (data.has("libraries")) {
            for (JsonNode lib : data.get("libraries")) {
                if (!libraryApplies(lib)) {
                    continue;
                }
                parts.add(libraryPath(lib.get("name").asText()).toString());
            }
        }
        if (data.has("jar")) {
            String jar = data.get("jar").asText();
            parts.add(mcDir.resolve("versions").resolve(jar).resolve(jar + ".jar").toString());
        } else if (data.has("inheritsFrom")) {
            String base = data.get("inheritsFrom").asText();
            parts.add(mcDir.resolve("versions").resolve(base).resolve(base + ".jar").toString());
        } else {
            parts.add(mcDir.resolve("versions").resolve(version).resolve(version + ".jar").toString());
        }
        return String.join(java.io.File.pathSeparator, parts);
    }

    private List<String> parseArgs(JsonNode args, Map<String, String> vars) {
        List<String> result = new ArrayList<>();
        for (JsonNode a : args) {
            if (a.isTextual()) {
                result.add(replace(a.asText(), vars));
            } else if (a.isObject()) {
                if (!ruleApplies(a.get("rules"))) {
                    continue;
                }
                JsonNode value = a.get("value");
                if (value.isTextual()) {
                    result.add(replace(value.asText(), vars));
                } else if (value.isArray()) {
                    for (JsonNode v : value) {
                        result.add(replace(v.asText(), vars));
                    }
                }
            }
        }
        return result;
    }

    private List<String> parseStringArgs(String argStr, Map<String, String> vars) {
        List<String> result = new ArrayList<>();
        for (String part : argStr.split(" ")) {
            if (!part.isEmpty()) {
                result.add(replace(part, vars));
            }
        }
        return result;
    }

    private String replace(String s, Map<String, String> vars) {
        for (Map.Entry<String, String> e : vars.entrySet()) {
            s = s.replace("${" + e.getKey() + "}", e.getValue());
        }
        return s;
    }

    private boolean libraryApplies(JsonNode lib) {
        if (!lib.has("rules")) {
            return true;
        }
        return ruleApplies(lib.get("rules"));
    }

    private boolean ruleApplies(JsonNode rules) {
        if (rules == null || !rules.isArray()) {
            return true;
        }
        for (JsonNode rule : rules) {
            if (!singleRuleApplies(rule)) {
                return false;
            }
        }
        return true;
    }

    private boolean singleRuleApplies(JsonNode rule) {
        boolean allow = "allow".equals(rule.get("action").asText());
        boolean osMatches = true;
        if (rule.has("os")) {
            JsonNode os = rule.get("os");
            if (os.has("name")) {
                osMatches = switch (os.get("name").asText()) {
                    case "windows" -> isWindows();
                    case "osx" -> isMac();
                    case "linux" -> isLinux();
                    default -> false;
                };
            }
            if (osMatches && os.has("arch")) {
                String arch = os.get("arch").asText();
                String cur = System.getProperty("os.arch").toLowerCase();
                osMatches = switch (arch) {
                    case "x86" -> cur.contains("86") || cur.contains("32");
                    case "arm64" -> cur.contains("aarch64") || cur.contains("arm64");
                    default -> true;
                };
            }
        }
        if (rule.has("features")) {
            return !allow;
        }
        return allow ? osMatches : !osMatches;
    }

    private String nativesKey(JsonNode lib) {
        JsonNode natives = lib.get("natives");
        if (isWindows() && natives.has("windows")) {
            return natives.get("windows").asText().replace("${arch}", "64");
        }
        if (isMac() && natives.has("osx")) {
            return natives.get("osx").asText().replace("${arch}", "64");
        }
        if (isLinux() && natives.has("linux")) {
            return natives.get("linux").asText().replace("${arch}", "64");
        }
        return null;
    }

    private Path libraryPath(String name) {
        String[] parts = name.split(":");
        String group = parts[0].replace('.', '/');
        String artifact = parts[1];
        String version = parts.length > 2 ? parts[2] : "";
        StringBuilder file = new StringBuilder(artifact).append('-').append(version);
        for (int i = 3; i < parts.length; i++) {
            file.append('-').append(parts[i]);
        }
        file.append(".jar");
        return mcDir.resolve("libraries").resolve(group).resolve(artifact).resolve(version)
                .resolve(file.toString());
    }

    private Path nativeLibraryPath(String name, String nativeKey) {
        String[] parts = name.split(":");
        String group = parts[0].replace('.', '/');
        String artifact = parts[1];
        String version = parts[2];
        String file = artifact + "-" + version + "-" + nativeKey + ".jar";
        return mcDir.resolve("libraries").resolve(group).resolve(artifact).resolve(version).resolve(file);
    }

    private String mavenUrl(String name, JsonNode lib) {
        String base = lib.has("url") ? lib.get("url").asText() : "https://libraries.minecraft.net/";
        if (!base.endsWith("/")) {
            base += "/";
        }
        String[] parts = name.split(":");
        String group = parts[0].replace('.', '/');
        String artifact = parts[1];
        String version = parts[2];
        StringBuilder file = new StringBuilder(artifact).append('-').append(version);
        for (int i = 3; i < parts.length; i++) {
            file.append('-').append(parts[i]);
        }
        file.append(".jar");
        return base + group + "/" + artifact + "/" + version + "/" + file;
    }

    private String libName(String name) {
        String[] parts = name.split(":");
        if (parts.length >= 2) {
            return parts[0] + ":" + parts[1];
        }
        return name;
    }

    private void extractZip(Path zip, Path destDir, JsonNode lib) throws IOException {
        Files.createDirectories(destDir);
        List<String> excludes = new ArrayList<>();
        if (lib.has("extract") && lib.get("extract").has("exclude")) {
            lib.get("extract").get("exclude").forEach(e -> excludes.add(e.asText()));
        }
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                boolean skip = false;
                for (String ex : excludes) {
                    if (entry.getName().startsWith(ex)) {
                        skip = true;
                        break;
                    }
                }
                if (skip) {
                    continue;
                }
                Path target = destDir.resolve(entry.getName());
                Files.createDirectories(target.getParent());
                try (InputStream in = zf.getInputStream(entry)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static boolean isMac() {
        return System.getProperty("os.name").toLowerCase().contains("mac");
    }

    private static boolean isLinux() {
        return System.getProperty("os.name").toLowerCase().contains("linux");
    }

    private static String sanitizeMem(String mem, String def) {
        if (mem == null) {
            return def;
        }
        String v = mem.trim().toUpperCase();
        if (v.matches("\\d+[GMK]")) {
            return v;
        }
        return def;
    }

    private void setGameLanguageToChinese(Path gameDir) {
        Path options = gameDir.resolve("options.txt");
        java.util.Map<String, String> opts = new java.util.LinkedHashMap<>();
        if (Files.exists(options)) {
            try {
                for (String line : Files.readAllLines(options)) {
                    int idx = line.indexOf(':');
                    if (idx > 0) {
                        opts.put(line.substring(0, idx), line.substring(idx + 1));
                    }
                }
            } catch (IOException ignored) {
            }
        }
        opts.put("lang", "zh_cn");
        try {
            List<String> lines = new ArrayList<>();
            for (var e : opts.entrySet()) {
                lines.add(e.getKey() + ":" + e.getValue());
            }
            Files.write(options, lines);
        } catch (IOException ignored) {
        }
    }
}