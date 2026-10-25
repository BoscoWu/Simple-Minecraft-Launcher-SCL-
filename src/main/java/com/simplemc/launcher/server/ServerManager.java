package com.simplemc.launcher.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simplemc.launcher.util.AppPaths;
import com.simplemc.launcher.util.Downloader;
import com.simplemc.launcher.util.JsonUtil;
import com.simplemc.launcher.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 服务器下载与启动（Paper / Purpur / 原版 / Fabric / Forge）。
 */
public class ServerManager {

    private final Path serverDir;
    private final Consumer<String> log;
    private final Consumer<String> status;
    private final BiConsumer<Long, Long> progress;

    private Process runningProcess;

    public ServerManager(Path serverDir, Consumer<String> log, Consumer<String> status,
                         BiConsumer<Long, Long> progress) {
        this.serverDir = serverDir;
        this.log = log;
        this.status = status;
        this.progress = progress;
    }

    public boolean installServer(String type, String version, String maxMem, String minMem) {
        String t = type.toLowerCase();
        try {
            status.accept("正在下载 " + t + " " + version + " 服务器...");
            Path dir = serverDir.resolve(t + "-" + version);
            Files.createDirectories(dir);

            String[] info = resolveDownload(t, version);
            String url = info[0];
            String jarName = info[1];
            Downloader.download(url, dir.resolve(jarName), progress);

            if ("forge".equals(t)) {
                status.accept("运行 Forge 安装器...");
                runForgeServerInstaller(dir, jarName);
            } else {
                writeEula(dir);
            }

            ObjectNode serverInfo = JsonUtil.newObject();
            serverInfo.put("type", t);
            serverInfo.put("version", version);
            serverInfo.put("jar", jarName);
            serverInfo.put("max_mem", maxMem);
            serverInfo.put("min_mem", minMem);
            Files.writeString(dir.resolve("server_info.json"), JsonUtil.stringifyPretty(serverInfo));

            log.accept("服务器 " + t + " " + version + " 下载完成！");
            return true;
        } catch (Exception e) {
            log.accept("下载服务器失败: " + e.getMessage());
            Log.error("下载服务器失败", e);
            return false;
        }
    }

    public Process launchServer(String serverId, String maxMem, String minMem) throws Exception {
        Path dir = serverDir.resolve(serverId);
        if (!Files.isDirectory(dir)) {
            throw new IOException("服务器 " + serverId + " 不存在");
        }
        // 读取 server_info.json
        String mx = maxMem, ms = minMem;
        Path infoPath = dir.resolve("server_info.json");
        if (Files.exists(infoPath)) {
            JsonNode info = JsonUtil.parse(Files.readString(infoPath));
            if (info.has("max_mem")) {
                mx = info.get("max_mem").asText();
            }
            if (info.has("min_mem")) {
                ms = info.get("min_mem").asText();
            }
        }
        Path jar = findServerJar(dir);
        if (jar == null) {
            throw new IOException("服务器目录中没有 .jar 文件");
        }
        List<String> cmd = new ArrayList<>();
        cmd.add("java");
        cmd.add("-Xmx" + mx);
        cmd.add("-Xms" + ms);
        cmd.add("-jar");
        cmd.add(jar.getFileName().toString());
        cmd.add("nogui");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir.toFile());
        pb.redirectErrorStream(true);
        runningProcess = pb.start();
        return runningProcess;
    }

    public void stopServer() {
        if (runningProcess != null && runningProcess.isAlive()) {
            runningProcess.destroy();
            runningProcess = null;
            log.accept("服务器已停止");
        }
    }

    /**
     * 向正在运行的服务器发送命令（如 stop、list 等）。
     */
    public void sendCommand(String command) {
        if (runningProcess == null || !runningProcess.isAlive()) {
            return;
        }
        try {
            var writer = new java.io.OutputStreamWriter(
                    runningProcess.getOutputStream(), java.nio.charset.StandardCharsets.UTF_8);
            writer.write(command + "\n");
            writer.flush();
        } catch (IOException e) {
            Log.warn("发送服务器命令失败: " + e.getMessage());
        }
    }

    public List<String> installedServers() {
        List<String> result = new ArrayList<>();
        if (!Files.isDirectory(serverDir)) {
            return result;
        }
        try (var stream = Files.list(serverDir)) {
            stream.filter(Files::isDirectory)
                    .map(d -> d.getFileName().toString())
                    .sorted(Comparator.naturalOrder())
                    .forEach(result::add);
        } catch (IOException e) {
            Log.warn("读取服务器列表失败: " + e.getMessage());
        }
        return result;
    }

    // ------------------------------------------------------------------
    private String[] resolveDownload(String type, String version) throws Exception {
        return switch (type) {
            case "paper" -> {
                JsonNode builds = Downloader.getJson(
                        "https://fill.papermc.io/v3/projects/paper/versions/" + version + "/builds");
                JsonNode build = null;
                for (JsonNode b : builds) {
                    if ("STABLE".equals(b.has("channel") ? b.get("channel").asText() : "")) {
                        build = b;
                        break;
                    }
                }
                if (build == null && builds.size() > 0) {
                    build = builds.get(0);
                }
                if (build == null) {
                    throw new IOException("Paper 不支持版本 " + version);
                }
                JsonNode dl = build.get("downloads").get("server:default");
                yield new String[]{dl.get("url").asText(), dl.get("name").asText()};
            }
            case "purpur" -> new String[]{
                    "https://api.purpurmc.org/v2/purpur/" + version + "/latest/download",
                    "purpur-" + version + ".jar"};
            case "vanilla" -> {
                JsonNode manifest = Downloader.getJson(
                        "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");
                String url = null;
                for (JsonNode v : manifest.get("versions")) {
                    if (v.get("id").asText().equals(version)) {
                        url = v.get("url").asText();
                        break;
                    }
                }
                if (url == null) {
                    throw new IOException("原版服务器版本 " + version + " 不存在");
                }
                JsonNode vdata = Downloader.getJson(url);
                yield new String[]{vdata.get("downloads").get("server").get("url").asText(),
                        "server-" + version + ".jar"};
            }
            case "fabric" -> {
                JsonNode loaders = Downloader.getJson(
                        "https://meta.fabricmc.net/v2/versions/loader/" + version);
                String loaderVersion = loaders.get(0).get("loader").get("version").asText();
                String meta = Downloader.getString(
                        "https://maven.fabricmc.net/net/fabricmc/fabric-installer/maven-metadata.xml");
                String installerVersion = extractLatest(meta);
                yield new String[]{
                        "https://meta.fabricmc.net/v2/versions/loader/" + version + "/"
                                + loaderVersion + "/" + installerVersion + "/server/jar",
                        "fabric-server-" + version + ".jar"};
            }
            case "forge" -> new String[]{
                    "https://maven.minecraftforge.net/net/minecraftforge/forge/" + version
                            + "/forge-" + version + "-installer.jar",
                    "forge-" + version + "-installer.jar"};
            default -> throw new IOException("不支持的服务器类型: " + type);
        };
    }

    private void runForgeServerInstaller(Path dir, String installerJar) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("java", "-jar", installerJar, "--installServer");
        pb.directory(dir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        int code = p.waitFor();
        if (code != 0) {
            String tail = output.length() > 1500 ? output.substring(output.length() - 1500) : output;
            throw new IOException("Forge 服务器安装失败:\n" + tail);
        }
        writeEula(dir);
    }

    private void writeEula(Path dir) throws IOException {
        Path eula = dir.resolve("eula.txt");
        if (!Files.exists(eula)) {
            Files.writeString(eula, "eula=true\n");
        }
    }

    private Path findServerJar(Path dir) {
        try (var stream = Files.list(dir)) {
            List<Path> jars = stream.filter(f -> f.getFileName().toString().endsWith(".jar"))
                    .filter(f -> !f.getFileName().toString().toLowerCase().contains("installer"))
                    .toList();
            if (!jars.isEmpty()) {
                return jars.get(0);
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private String extractLatest(String xml) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<latest>([^<]+)</latest>").matcher(xml);
        return m.find() ? m.group(1) : null;
    }
}
