package com.simplemc.launcher.minecraft;

import com.fasterxml.jackson.databind.JsonNode;
import com.simplemc.launcher.util.AppPaths;
import com.simplemc.launcher.util.Downloader;
import com.simplemc.launcher.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模组加载器安装（Fabric / Forge / NeoForge / Quilt）。
 * 返回安装器生成的原始版本 id，由调用方负责统一重命名。
 */
public class ModLoaderManager {

    private static final Pattern TAG = Pattern.compile("<(latest|release|version)>([^<]+)</\\1>");

    private final Path mcDir;
    private final Consumer<String> log;
    private final Consumer<String> status;
    private final BiConsumer<Long, Long> progress;

    public ModLoaderManager(Path mcDir, Consumer<String> log, Consumer<String> status,
                            BiConsumer<Long, Long> progress) {
        this.mcDir = mcDir;
        this.log = log;
        this.status = status;
        this.progress = progress;
    }

    public String install(String loader, String mcVersion, AtomicBoolean cancelFlag) throws Exception {
        return switch (loader) {
            case "fabric" -> installFabric(mcVersion, cancelFlag);
            case "forge" -> installForge(mcVersion, cancelFlag);
            case "neoforge" -> installNeoForge(mcVersion, cancelFlag);
            case "quilt" -> installQuilt(mcVersion, cancelFlag);
            default -> throw new IOException("不支持的加载器: " + loader);
        };
    }

    private String installFabric(String mcVersion, AtomicBoolean cancelFlag) throws Exception {
        status.accept("正在安装 Fabric...");
        JsonNode loaders = Downloader.getJson(
                "https://meta.fabricmc.net/v2/versions/loader/" + mcVersion);
        String loaderVersion = loaders.get(0).get("loader").get("version").asText();
        String installerVersion = latestFromXml(
                Downloader.getString("https://maven.fabricmc.net/net/fabricmc/fabric-installer/maven-metadata.xml"));
        String url = "https://maven.fabricmc.net/net/fabricmc/fabric-installer/" + installerVersion
                + "/fabric-installer-" + installerVersion + ".jar";
        return installViaInstaller(cancelFlag, url, "client", "-dir", mcDir.toString(),
                "-mcversion", mcVersion, "-loader", loaderVersion, "-noprofile");
    }

    private String installQuilt(String mcVersion, AtomicBoolean cancelFlag) throws Exception {
        status.accept("正在安装 Quilt...");
        JsonNode loaders = Downloader.getJson("https://meta.quiltmc.org/v3/versions/loader");
        String loaderVersion = loaders.get(0).get("version").asText();
        String installerVersion = latestFromXml(Downloader.getString(
                "https://maven.quiltmc.org/repository/release/org/quiltmc/quilt-installer/maven-metadata.xml"));
        String url = "https://maven.quiltmc.org/repository/release/org/quiltmc/quilt-installer/"
                + installerVersion + "/quilt-installer-" + installerVersion + ".jar";
        return installViaInstaller(cancelFlag, url, "install", "client", mcVersion, loaderVersion,
                "--install-dir=" + mcDir, "--no-profile");
    }

    private String installForge(String mcVersion, AtomicBoolean cancelFlag) throws Exception {
        status.accept("正在安装 Forge...");
        List<String> versions = versionsFromXml(Downloader.getString(
                "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml"));
        String forgeVersion = null;
        for (String v : versions) {
            if (v.startsWith(mcVersion + "-")) {
                forgeVersion = v;
            }
        }
        if (forgeVersion == null) {
            throw new IOException("未找到适用于 Minecraft " + mcVersion + " 的 Forge 版本");
        }
        String url = "https://maven.minecraftforge.net/net/minecraftforge/forge/" + forgeVersion
                + "/forge-" + forgeVersion + "-installer.jar";
        return installViaInstaller(cancelFlag, url, "--install-client", mcDir.toString());
    }

    private String installNeoForge(String mcVersion, AtomicBoolean cancelFlag) throws Exception {
        status.accept("正在安装 NeoForge...");
        String mirrorBase = "https://bmclapi2.bangbang93.com/maven";
        List<String> versions = versionsFromXml(Downloader.getString(
                mirrorBase + "/net/neoforged/neoforge/maven-metadata.xml"));
        String loaderVersion = null;
        for (String v : versions) {
            if (unstable(v)) {
                continue;
            }
            if (neoforgeMcVersion(v).equals(mcVersion)) {
                if (loaderVersion == null || numKey(v).compareTo(numKey(loaderVersion)) > 0) {
                    loaderVersion = v;
                }
            }
        }
        if (loaderVersion == null) {
            throw new IOException("未找到适用于 Minecraft " + mcVersion + " 的 NeoForge 版本");
        }
        String url = mirrorBase + "/net/neoforged/neoforge/" + loaderVersion
                + "/neoforge-" + loaderVersion + "-installer.jar";
        return installViaInstaller(cancelFlag, url, "--install-client", mcDir.toString(),
                "--mirror", mirrorBase);
    }

    /**
     * 下载安装器并运行，返回新增的版本目录 id。
     */
    private String installViaInstaller(AtomicBoolean cancelFlag, String url, String... args) throws Exception {
        Set<String> before = listVersionDirs();
        Path installer = Files.createTempFile("mc-installer-", ".jar");
        try {
            status.accept("下载安装器...");
            // 正确调用：url, dest, sha1(null), progress, cancelFlag
            Downloader.download(url, installer, null, progress, cancelFlag);
            if (cancelFlag.get()) {
                throw new InterruptedException("下载已取消");
            }
            status.accept("运行安装器...");
            runInstaller(installer, args);
        } finally {
            try {
                Files.deleteIfExists(installer);
            } catch (IOException ignored) {
            }
        }
        Set<String> after = listVersionDirs();
        after.removeAll(before);
        if (after.isEmpty()) {
            throw new IOException("安装器未创建任何版本目录");
        }
        return after.stream().sorted().findFirst().orElseThrow();
    }

    private void runInstaller(Path installer, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(findJava());
        cmd.add("-jar");
        cmd.add(installer.toString());
        for (String a : args) {
            cmd.add(a);
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        if (code != 0) {
            String tail = output.length() > 2000 ? output.substring(output.length() - 2000) : output;
            throw new IOException("安装器执行失败（退出码 " + code + "）:\n" + tail);
        }
        log.accept("安装器执行完成");
    }

    private Set<String> listVersionDirs() {
        Set<String> result = new HashSet<>();
        Path versionsDir = mcDir.resolve("versions");
        if (!Files.isDirectory(versionsDir)) {
            return result;
        }
        try (var stream = Files.list(versionsDir)) {
            stream.filter(Files::isDirectory)
                    .forEach(d -> result.add(d.getFileName().toString()));
        } catch (IOException e) {
            Log.warn("读取版本目录失败: " + e.getMessage());
        }
        return result;
    }

    private String findJava() {
        Path javaDir = AppPaths.JAVA_DIR;
        if (Files.isDirectory(javaDir)) {
            try (var stream = Files.list(javaDir)) {
                List<Path> dirs = stream.filter(Files::isDirectory)
                        .sorted(Comparator.reverseOrder()).toList();
                for (Path d : dirs) {
                    Path exe = d.resolve("bin/java.exe");
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
        return "java";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private String latestFromXml(String xml) {
        Matcher m = TAG.matcher(xml);
        String latest = null;
        String release = null;
        while (m.find()) {
            String tag = m.group(1);
            if ("latest".equals(tag)) {
                latest = m.group(2);
            } else if ("release".equals(tag)) {
                release = m.group(2);
            }
        }
        return latest != null ? latest : release;
    }

    private List<String> versionsFromXml(String xml) {
        List<String> result = new ArrayList<>();
        Matcher m = Pattern.compile("<version>([^<]+)</version>").matcher(xml);
        while (m.find()) {
            result.add(m.group(1));
        }
        return result;
    }

    private boolean unstable(String v) {
        String low = v.toLowerCase();
        return low.contains("alpha") || low.contains("beta") || low.contains("snapshot")
                || low.contains("pre") || low.contains("rc") || low.contains("nightly")
                || low.contains("craftmine");
    }

    private String neoforgeMcVersion(String v) {
        String[] parts = v.split("\\.");
        if (parts.length >= 2 && parts[0].matches("\\d+") && parts[1].matches("\\d+")) {
            return "1." + parts[0] + "." + parts[1];
        }
        return v;
    }

    private String numKey(String v) {
        Matcher m = Pattern.compile("\\d+").matcher(v);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String g = m.group();
            sb.append(String.format("%10s", g));
        }
        return sb.toString();
    }
}