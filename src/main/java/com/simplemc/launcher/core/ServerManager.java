package com.simplemc.launcher.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.simplemc.launcher.util.Constants;
import com.simplemc.launcher.util.Downloader;
import com.simplemc.launcher.util.Http;
import com.simplemc.launcher.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 服务器管理：安装、启动、EULA、控制台、停止、删除。
 */
public final class ServerManager {

    public enum ServerType {
        VANILLA("vanilla"), PAPER("paper"), PURPUR("purpur"),
        FABRIC("fabric"), FORGE("forge"), NEOFORGE("neoforge");
        public final String id;
        ServerType(String id) { this.id = id; }
        public static ServerType of(String s) {
            for (ServerType t : values()) {
                if (t.id.equalsIgnoreCase(s)) return t;
            }
            return VANILLA;
        }
    }

    private final Path serverRoot;
    private Process process;
    private Path currentJar;
    private String currentServerId;
    private final List<Consumer<String>> logListeners = new ArrayList<>();
    private final List<Runnable> exitListeners = new ArrayList<>();

    public ServerManager(Path serverRoot) {
        this.serverRoot = serverRoot;
    }

    // ---------------- 列表 ----------------

    public List<String> listServers() {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(serverRoot)) return out;
        try (var s = Files.list(serverRoot)) {
            s.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .forEach(out::add);
        } catch (Exception e) {
            Log.warn("枚举服务器失败: " + e.getMessage());
        }
        return out;
    }

    public Path serverDir(String id) {
        return serverRoot.resolve(id);
    }

    public long serverSize(String id) {
        Path dir = serverDir(id);
        if (!Files.isDirectory(dir)) return 0;
        try (var s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try { return Files.size(p); } catch (Exception e) { return 0; }
                    }).sum();
        } catch (Exception e) {
            return 0;
        }
    }

    // ---------------- 安装 ----------------

    /**
     * 安装服务端。
     *
     * @return 服务器 ID（目录名）
     */
    public String install(ServerType type, String mcVersion, String maxMem, String minMem,
                          Consumer<String> stage) throws Exception {
        stage.accept("准备安装 " + type.id + " 服务端 " + mcVersion + " ...");

        String id = type.id + "-" + mcVersion;
        Path dir = serverDir(id);
        Files.createDirectories(dir);

        String jarName;
        Path jarPath;

        switch (type) {
            case PAPER -> {
                String url = paperDownloadUrl(mcVersion);
                jarName = "paper-" + mcVersion + "-latest.jar";
                jarPath = dir.resolve(jarName);
                stage.accept("下载 Paper 服务端...");
                Downloader.download(List.of(url), jarPath, null, null);
            }
            case PURPUR -> {
                String url = "https://api.purpurmc.org/v2/purpur/" + mcVersion + "/latest/download";
                jarName = "purpur-" + mcVersion + ".jar";
                jarPath = dir.resolve(jarName);
                stage.accept("下载 Purpur 服务端...");
                Downloader.download(List.of(url), jarPath, null, null);
            }
            case FABRIC -> {
                String[] info = fabricServerInfo(mcVersion);
                String url = info[0];
                jarName = info[1];
                jarPath = dir.resolve(jarName);
                stage.accept("下载 Fabric 服务端...");
                Downloader.download(List.of(url), jarPath, null, null);
            }
            case VANILLA -> {
                String url = vanilllaServerUrl(mcVersion);
                jarName = "server-" + mcVersion + ".jar";
                jarPath = dir.resolve(jarName);
                stage.accept("下载原版服务端...");
                Downloader.download(List.of(url), jarPath, null, null);
            }
            case FORGE, NEOFORGE -> {
                stage.accept("Forge/NeoForge 服务端安装器较复杂，正在准备...");
                jarPath = installForgeServer(type, mcVersion, dir, stage);
                jarName = jarPath.getFileName().toString();
            }
            default -> throw new IllegalStateException("不支持的服务端类型");
        }

        // 写 eula.txt（默认 false，首次启动时由界面询问）
        Path eula = dir.resolve("eula.txt");
        if (!Files.isRegularFile(eula)) {
            Files.writeString(eula,
                    "# By changing the setting below to TRUE you are indicating "
                    + "your agreement to our EULA (https://aka.ms/MinecraftEULA).\n"
                    + "eula=false\n", StandardCharsets.UTF_8);
        }

        // server.properties 基础配置
        Path props = dir.resolve("server.properties");
        if (!Files.isRegularFile(props)) {
            Files.writeString(props,
                    "server-port=25565\nmotd=A Minecraft Server\nmax-players=20\n"
                    + "online-mode=true\ngamemode=survival\ndifficulty=easy\n"
                    + "level-name=world\npvp=true\n", StandardCharsets.UTF_8);
        }

        // server_info.json
        String info = Http.MAPPER.writeValueAsString(java.util.Map.of(
                "type", type.id,
                "mc_version", mcVersion,
                "jar", jarName,
                "max_mem", maxMem,
                "min_mem", minMem));
        Files.writeString(dir.resolve("server_info.json"), info, StandardCharsets.UTF_8);

        // 启动脚本
        writeStartScript(dir, jarName, maxMem, minMem);

        stage.accept("服务端安装完成: " + id);
        Log.info("服务端 " + id + " 安装完成");
        return id;
    }

    private void writeStartScript(Path dir, String jarName, String maxMem, String minMem)
            throws Exception {
        if (isWindows()) {
            String bat = "@echo off\r\n"
                    + "java -Xmx" + maxMem + " -Xms" + minMem + " -jar " + jarName + " nogui\r\n"
                    + "pause\r\n";
            Files.writeString(dir.resolve("start.bat"), bat, StandardCharsets.UTF_8);
        } else {
            String sh = "#!/bin/sh\n"
                    + "java -Xmx" + maxMem + " -Xms" + minMem + " -jar " + jarName + " nogui\n";
            Path p = dir.resolve("start.sh");
            Files.writeString(p, sh, StandardCharsets.UTF_8);
            try { p.toFile().setExecutable(true); } catch (Exception ignored) {}
        }
    }

    /** Paper 下载地址 */
    private String paperDownloadUrl(String mcVersion) throws Exception {
        JsonNode builds = Http.fetchJson(
                "https://api.papermc.io/v2/projects/paper/versions/" + mcVersion + "/builds",
                Duration.ofSeconds(25), null);
        JsonNode arr = builds.path("builds");
        if (!arr.isArray() || arr.isEmpty()) {
            throw new IllegalStateException("Paper 没有 " + mcVersion + " 的构建");
        }
        JsonNode last = arr.get(arr.size() - 1);
        int build = last.path("build").asInt();
        String jar = last.path("downloads").path("application").path("name").asText();
        return "https://api.papermc.io/v2/projects/paper/versions/"
                + mcVersion + "/builds/" + build + "/downloads/" + jar;
    }

    /**
     * Fabric 服务端：需要 MC 版本 + 加载器版本 + 安装器版本三段。
     *
     * @return [url, fileName]
     */
    private String[] fabricServerInfo(String mcVersion) throws Exception {
        if (!mcVersion.matches("1\\.\\d{1,2}(\\.\\d{1,2})?(-\\w+)?")) {
            throw new IllegalArgumentException(
                    "Fabric 服务端的版本号应为 Minecraft 版本（如 1.21.1），"
                    + "而不是加载器版本。当前输入：" + mcVersion);
        }
        JsonNode loaders = Http.fetchJson(
                "https://meta.fabricmc.net/v2/versions/loader/" + mcVersion);
        String loaderVersion = null;
        if (loaders.isArray()) {
            for (JsonNode l : loaders) {
                if (l.path("loader").path("stable").asBoolean(false)) {
                    loaderVersion = l.path("loader").path("version").asText();
                    break;
                }
            }
            if (loaderVersion == null && !loaders.isEmpty()) {
                loaderVersion = loaders.get(0).path("loader").path("version").asText();
            }
        }
        if (loaderVersion == null) {
            throw new IllegalStateException("Fabric 找不到 " + mcVersion + " 的加载器");
        }
        String installerVersion = fabricInstallerVersion();
        String url = "https://meta.fabricmc.net/v2/versions/loader/" + mcVersion
                + "/" + loaderVersion + "/" + installerVersion + "/server/jar";
        String fileName = "fabric-server-mc." + mcVersion
                + "-loader." + loaderVersion + ".jar";
        return new String[]{url, fileName};
    }

    /** 从 maven-metadata.xml 解析 Fabric 安装器版本（注意是 XML 不是 JSON） */
    private String fabricInstallerVersion() {
        try {
            String xml = new String(Http.get(
                    "https://maven.fabricmc.net/net/fabricmc/fabric-installer/maven-metadata.xml",
                    Duration.ofSeconds(20)).body(), StandardCharsets.UTF_8);
            var m = java.util.regex.Pattern.compile("<release>([^<]+)</release>").matcher(xml);
            if (m.find()) return m.group(1).trim();
            var m2 = java.util.regex.Pattern.compile("<latest>([^<]+)</latest>").matcher(xml);
            if (m2.find()) return m2.group(1).trim();
        } catch (Exception e) {
            Log.warn("解析 Fabric 安装器版本失败: " + e.getMessage());
        }
        return "1.0.1";
    }

    /** 原版服务端下载地址 */
    private String vanilllaServerUrl(String mcVersion) throws Exception {
        JsonNode manifest = Http.fetchJson(
                "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json",
                Duration.ofSeconds(25), null);
        String versionUrl = null;
        for (JsonNode v : manifest.path("versions")) {
            if (v.path("id").asText().equals(mcVersion)) {
                versionUrl = v.path("url").asText();
                break;
            }
        }
        if (versionUrl == null) {
            throw new IllegalStateException("找不到 MC 版本 " + mcVersion);
        }
        JsonNode vjson = Http.fetchJson(versionUrl);
        JsonNode server = vjson.path("downloads").path("server");
        if (server.isMissingNode()) {
            throw new IllegalStateException(mcVersion + " 没有提供服务端下载");
        }
        return server.path("url").asText();
    }

    private Path installForgeServer(ServerType type, String mcVersion, Path dir,
                                    Consumer<String> stage) throws Exception {
        // 简化实现：提示用户 Forge 服务端需要手动安装
        throw new IllegalStateException(
                "Forge/NeoForge 服务端安装较复杂，建议使用 Paper / Purpur / Fabric 服务端"
                + "（它们性能更好且支持 Forge 模组的部分功能）");
    }

    // ---------------- EULA ----------------

    public static boolean eulaAccepted(Path serverDir) {
        Path eula = serverDir.resolve("eula.txt");
        if (!Files.isRegularFile(eula)) return false;
        try {
            for (String line : Files.readAllLines(eula, StandardCharsets.UTF_8)) {
                String s = line.trim().toLowerCase();
                if (s.startsWith("#") || !s.contains("=")) continue;
                if (s.replace(" ", "").startsWith("eula=")) {
                    return s.split("=", 2)[1].trim().equals("true");
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    public static void setEula(Path serverDir, boolean accept) throws Exception {
        Files.createDirectories(serverDir);
        Files.writeString(serverDir.resolve("eula.txt"),
                "# By changing the setting below to TRUE you are indicating "
                + "your agreement to our EULA (https://aka.ms/MinecraftEULA).\n"
                + "eula=" + (accept ? "true" : "false") + "\n",
                StandardCharsets.UTF_8);
    }

    // ---------------- 启动 / 停止 ----------------

    public void addLogListener(Consumer<String> l) { logListeners.add(l); }
    public void addExitListener(Runnable r) { exitListeners.add(r); }

    private void emitLog(String line) {
        for (Consumer<String> l : logListeners) {
            try { l.accept(line); } catch (Exception ignored) {}
        }
    }

    public boolean isRunning() {
        return process != null && process.isAlive();
    }

    public String currentServerId() { return currentServerId; }

    /** 启动服务器 */
    public void start(String serverId, String javaBin, String maxMem, String minMem)
            throws Exception {
        Path dir = serverDir(serverId);
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("服务器目录不存在: " + serverId);
        }
        Path jar = findServerJar(dir);
        if (jar == null) {
            throw new IllegalStateException("服务器目录里没有 jar 文件");
        }

        currentJar = jar;
        currentServerId = serverId;

        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin);
        cmd.add("-Xmx" + maxMem);
        cmd.add("-Xms" + minMem);
        cmd.add("-jar");
        cmd.add(jar.toAbsolutePath().toString());
        cmd.add("nogui");

        emitLog("[系统] 启动服务器 " + serverId);
        Log.info("启动服务器: " + String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir.toFile());
        pb.redirectErrorStream(true);
        process = pb.start();

        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    emitLog(line);
                }
            } catch (Exception ignored) {
            } finally {
                emitLog("[系统] 服务器已停止");
                for (Runnable rr : exitListeners) {
                    try { rr.run(); } catch (Exception ignored) {}
                }
            }
        }, "server-log-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private Path findServerJar(Path dir) {
        try (var s = Files.list(dir)) {
            List<Path> jars = s.filter(p -> p.toString().endsWith(".jar"))
                    .sorted().toList();
            // 优先不含 installer 的
            for (Path j : jars) {
                if (!j.getFileName().toString().toLowerCase().contains("installer")) {
                    return j;
                }
            }
            return jars.isEmpty() ? null : jars.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    /** 发送命令到服务器控制台 */
    public void sendCommand(String cmd) {
        if (!isRunning()) {
            emitLog("[系统] 服务器未运行");
            return;
        }
        try {
            OutputStream os = process.getOutputStream();
            os.write((cmd + "\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
            emitLog("> " + cmd);
        } catch (Exception e) {
            emitLog("[系统] 发送命令失败: " + e.getMessage());
        }
    }

    /**
     * 停止服务器：先发 stop 优雅关闭，超时再强制结束进程树。
     */
    public void stop() {
        if (process == null) {
            emitLog("[系统] 没有正在运行的服务器进程");
            return;
        }
        if (!process.isAlive()) {
            process = null;
            return;
        }
        emitLog("[系统] 正在停止服务器...");
        long pid = process.pid();

        boolean graceful = false;
        try {
            sendCommand("stop");
            graceful = process.waitFor(8, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }

        if (!graceful) {
            killTree(pid);
        }
        // 兜底：按 jar 名清理残留
        killLeftoverJava();
        process = null;
        currentJar = null;
        emitLog("[系统] 服务器已停止");
    }

    /** 强制结束进程树 */
    private void killTree(long pid) {
        try {
            if (isWindows()) {
                new ProcessBuilder("taskkill", "/F", "/T", "/PID", String.valueOf(pid))
                        .redirectErrorStream(true)
                        .start().waitFor(15, TimeUnit.SECONDS);
                Log.info("已强制结束进程树 PID=" + pid);
            } else {
                try {
                    new ProcessBuilder("kill", "-9", "-" + pid).start()
                            .waitFor(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    new ProcessBuilder("kill", "-9", String.valueOf(pid)).start();
                }
            }
        } catch (Exception e) {
            Log.warn("强制结束进程失败: " + e.getMessage());
            try { process.destroyForcibly(); } catch (Exception ignored) {}
        }
    }

    /** 清理命令行中含该 jar 的残留 java 进程 */
    private void killLeftoverJava() {
        if (!isWindows() || currentJar == null) return;
        String jarName = currentJar.getFileName().toString();
        try {
            Process p = new ProcessBuilder("powershell", "-NoProfile", "-Command",
                    "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | "
                    + "Where-Object { $_.CommandLine -and $_.CommandLine.Contains('"
                    + jarName + "') } | ForEach-Object { $_.ProcessId }")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor(20, TimeUnit.SECONDS);
            for (String line : out.split("\\R")) {
                String pid = line.trim();
                if (pid.matches("\\d+")) {
                    new ProcessBuilder("taskkill", "/F", "/T", "/PID", pid)
                            .start().waitFor(10, TimeUnit.SECONDS);
                    Log.info("清理残留 java 进程 PID=" + pid);
                }
            }
        } catch (Exception e) {
            Log.warn("清理残留进程失败: " + e.getMessage());
        }
    }

    // ---------------- 删除 ----------------

    public boolean delete(String serverId) {
        Path dir = serverDir(serverId);
        if (!Files.isDirectory(dir)) return false;

        // 如果正在运行的是这个服务器，先停掉
        if (isRunning() && serverId.equals(currentServerId)) {
            stop();
        }
        if (currentJar != null) {
            killLeftoverJava();
        }

        for (int attempt = 0; attempt < 5; attempt++) {
            try (var s = Files.walk(dir)) {
                s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                });
                Log.info("已删除服务器 " + serverId);
                return true;
            } catch (Exception e) {
                try { Thread.sleep(400L * (attempt + 1)); } catch (Exception ignored) {}
            }
        }
        return !Files.exists(dir);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
