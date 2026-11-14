package com.simplemc.launcher.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.simplemc.launcher.util.Constants;
import com.simplemc.launcher.util.Downloader;
import com.simplemc.launcher.util.Http;
import com.simplemc.launcher.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Java 运行时检测与安装（使用 Adoptium Temurin）。
 */
public final class JavaManager {

    /** 可用的 Java 信息 */
    public record JavaInfo(String path, String version, String vendor, int major) {
        @Override
        public String toString() {
            return "Java " + major + " (" + vendor + ")  " + path;
        }
    }

    private static final String ADOPTIUM_API =
            "https://api.adoptium.net/v3/assets/latest/%d/hotspot";

    /**
     * 检测系统里可用的 Java。
     */
    public static List<JavaInfo> detectAll() {
        List<JavaInfo> found = new ArrayList<>();

        List<String> candidates = new ArrayList<>();
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) {
            candidates.add(Path.of(javaHome, "bin", exe("java")).toString());
        }
        // PATH 里的 java
        candidates.add("java");
        // 常见安装位置
        if (isWindows()) {
            String pf = System.getenv("ProgramFiles");
            if (pf != null) {
                Path j = Path.of(pf, "Java");
                if (Files.isDirectory(j)) {
                    try (var s = Files.list(j)) {
                        s.filter(Files::isDirectory).forEach(d ->
                                candidates.add(d.resolve("bin").resolve("java.exe").toString()));
                    } catch (Exception ignored) {}
                }
                Path eclipse = Path.of(pf, "Eclipse Adoptium");
                if (Files.isDirectory(eclipse)) {
                    try (var s = Files.list(eclipse)) {
                        s.filter(Files::isDirectory).forEach(d ->
                                candidates.add(d.resolve("bin").resolve("java.exe").toString()));
                    } catch (Exception ignored) {}
                }
            }
            String commonFiles = System.getenv("CommonProgramFiles");
            if (commonFiles != null) {
                candidates.add(Path.of(commonFiles, "Oracle", "Java", "javapath",
                        "java.exe").toString());
            }
        }
        // 启动器自带目录
        candidates.add(Constants.JAVA_DIR.resolve("bin").resolve(exe("java")).toString());

        for (String c : candidates) {
            JavaInfo info = probe(c);
            if (info != null && found.stream().noneMatch(f -> f.path().equals(info.path()))) {
                found.add(info);
            }
        }
        found.sort((a, b) -> Integer.compare(b.major(), a.major()));
        return found;
    }

    /** 探测单个 java 可执行文件 */
    public static JavaInfo probe(String javaPath) {
        try {
            ProcessBuilder pb = new ProcessBuilder(javaPath, "-version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            String text = sb.toString();
            if (text.isBlank()) return null;

            String version = "";
            int major = 0;
            // 形如 java version "21.0.12" 或 openjdk version "17.0.2"
            var m = java.util.regex.Pattern
                    .compile("\"([0-9]+(?:\\.[0-9]+)*)\"")
                    .matcher(text);
            if (m.find()) {
                version = m.group(1);
                String[] parts = version.split("\\.");
                if (parts.length >= 2 && "1".equals(parts[0])) {
                    major = Integer.parseInt(parts[1]);
                } else {
                    major = Integer.parseInt(parts[0]);
                }
            }
            String vendor = "Unknown";
            if (text.contains("Temurin")) vendor = "Temurin";
            else if (text.contains("Oracle")) vendor = "Oracle";
            else if (text.contains("OpenJDK")) vendor = "OpenJDK";
            else if (text.contains("Microsoft")) vendor = "Microsoft";
            else if (text.contains("Zulu")) vendor = "Zulu";

            return new JavaInfo(javaPath, version, vendor, major);
        } catch (Exception e) {
            return null;
        }
    }

    /** 选择适合该 Minecraft 版本的 Java 主版本 */
    public static int requiredJavaMajor(String mcVersion) {
        try {
            String[] p = mcVersion.replaceAll("[^0-9.]", " ").trim().split("\\s+")[0].split("\\.");
            int minor = Integer.parseInt(p.length > 1 ? p[1] : "0");
            if (minor >= 20 && !mcVersion.contains("1.20.0") && !mcVersion.equals("1.20")) {
                // 1.20.5+ 需要 21
                if (p.length > 2) {
                    int patch = Integer.parseInt(p[2].replaceAll("\\D.*", ""));
                    return patch >= 5 ? 21 : 17;
                }
                return 21;
            }
            if (minor >= 18) return 17;
            if (minor >= 17) return 16;
            return 8;
        } catch (Exception e) {
            return 17;
        }
    }

    /**
     * 选出最合适的 Java：优先满足版本要求且版本号最低的（避免过新不兼容）。
     */
    public static JavaInfo pickBest(List<JavaInfo> all, int requiredMajor) {
        if (all.isEmpty()) return null;
        JavaInfo best = null;
        for (JavaInfo j : all) {
            if (j.major() >= requiredMajor) {
                if (best == null || j.major() < best.major()) {
                    best = j;
                }
            }
        }
        return best != null ? best : all.get(0);
    }

    /**
     * 下载安装 Java。
     *
     * @param major    主版本（8/11/17/21）
     * @param onStage  阶段回调
     */
    public static Path install(int major, java.util.function.Consumer<String> onStage)
            throws Exception {
        String os = isWindows() ? "windows" : (isMac() ? "mac" : "linux");
        String arch = isAarch64() ? "aarch64" : "x64";
        String url = String.format("%s?architecture=%s&image_type=jdk&os=%s&vendor=eclipse",
                String.format(ADOPTIUM_API, major), arch, os);

        onStage.accept("查询 Java " + major + " 下载地址...");
        JsonNode assets = Http.fetchJson(url);
        if (!assets.isArray() || assets.isEmpty()) {
            throw new IllegalStateException("找不到 Java " + major + " 的安装包");
        }
        JsonNode first = assets.get(0);
        JsonNode pkg = first.path("binary").path("package");
        String link = pkg.path("link").asText();
        String name = pkg.path("name").asText("jdk.zip");
        String sha = pkg.path("checksum").asText("");
        if (link.isBlank()) {
            throw new IllegalStateException("下载链接为空");
        }

        Path cacheDir = Constants.JAVA_DIR.resolve(".cache");
        Files.createDirectories(cacheDir);
        Path archive = cacheDir.resolve(name);

        onStage.accept("下载 Java " + major + "（"
                + Downloader.humanSize(pkg.path("size").asLong(0)) + "）");
        Downloader.download(List.of(link), archive, null, sha.isBlank() ? null : sha);

        onStage.accept("解压 Java...");
        Path target = Constants.JAVA_DIR.resolve("jdk-" + major);
        if (Files.isDirectory(target)) {
            deleteTree(target);
        }
        Files.createDirectories(target);

        if (name.endsWith(".zip")) {
            unzip(archive, target);
        } else {
            throw new IllegalStateException("暂不支持该安装包格式: " + name);
        }

        // Adoptium 的 zip 解压后通常多一层目录，拍平
        Path javaBin = findJavaExecutable(target);
        if (javaBin == null) {
            throw new IllegalStateException("解压后找不到 java 可执行文件");
        }
        Files.deleteIfExists(archive);
        onStage.accept("Java " + major + " 安装完成: " + javaBin);
        return javaBin;
    }

    private static Path findJavaExecutable(Path root) {
        try (var s = Files.walk(root, 4)) {
            return s.filter(p -> p.getFileName().toString().equals(exe("java")))
                    .filter(Files::isRegularFile)
                    .findFirst().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static void unzip(Path zip, Path dest) throws Exception {
        try (var zis = new java.util.zip.ZipInputStream(Files.newInputStream(zip))) {
            java.util.zip.ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                Path out = dest.resolve(e.getName()).normalize();
                if (!out.startsWith(dest)) continue;   // 防目录穿越
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(zis, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
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

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    static boolean isAarch64() {
        return System.getProperty("os.arch", "").contains("aarch64");
    }

    private static String exe(String name) {
        return isWindows() ? name + ".exe" : name;
    }
}
