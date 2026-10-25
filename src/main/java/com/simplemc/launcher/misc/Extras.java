package com.simplemc.launcher.misc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simplemc.launcher.util.AppPaths;
import com.simplemc.launcher.util.Downloader;
import com.simplemc.launcher.util.JsonUtil;
import com.simplemc.launcher.util.Log;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 其它功能：Java 运行时下载、frp 管理、DeepSeek 对话。
 */
public final class Extras {

    // ------------------------------------------------------------------
    // Java 运行时（Adoptium）
    // ------------------------------------------------------------------
    public static boolean installJava(String majorVersion, Consumer<String> log, Consumer<String> status,
                                      BiConsumer<Long, Long> progress) {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            String osName = os.contains("win") ? "windows" : (os.contains("mac") ? "mac" : "linux");
            String arch = System.getProperty("os.arch").toLowerCase().contains("aarch64") ? "aarch64" : "x64";
            String url = "https://api.adoptium.net/v3/assets/latest/" + majorVersion
                    + "/hotspot?architecture=" + arch + "&image_type=jre&os=" + osName;
            JsonNode data = Downloader.getJson(url);
            if (data.size() == 0) {
                log.accept("未找到 Java " + majorVersion + " 的可用下载");
                return false;
            }
            JsonNode pkg = data.get(0).get("binary").get("package");
            String link = pkg.get("link").asText();
            String name = pkg.get("name").asText();
            Path target = AppPaths.JAVA_DIR.resolve(name);
            if (Files.isDirectory(target) && hasJavaBin(target)) {
                log.accept("Java " + majorVersion + " 已安装");
                return true;
            }
            status.accept("正在下载 Java " + majorVersion + "...");
            Path zip = Files.createTempFile("jre-", ".zip");
            try {
                Downloader.download(link, zip, progress);
                status.accept("正在解压...");
                Files.createDirectories(target);
                try (ZipFile zf = new ZipFile(zip.toFile())) {
                    var entries = zf.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry e = entries.nextElement();
                        if (e.isDirectory()) {
                            continue;
                        }
                        // 去掉压缩包内第一层目录
                        String rel = e.getName();
                        int slash = rel.indexOf('/');
                        if (slash >= 0) {
                            rel = rel.substring(slash + 1);
                        }
                        if (rel.isEmpty()) {
                            continue;
                        }
                        Path out = target.resolve(rel);
                        Files.createDirectories(out.getParent());
                        try (InputStream in = zf.getInputStream(e)) {
                            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
                log.accept("Java " + majorVersion + " 安装完成");
                return true;
            } finally {
                try {
                    Files.deleteIfExists(zip);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            log.accept("安装 Java 失败: " + e.getMessage());
            Log.error("安装 Java 失败", e);
            return false;
        }
    }

    public static List<String> listJava() {
        List<String> result = new ArrayList<>();
        if (Files.isDirectory(AppPaths.JAVA_DIR)) {
            try (var stream = Files.list(AppPaths.JAVA_DIR)) {
                stream.filter(Files::isDirectory).sorted(Comparator.reverseOrder()).forEach(d -> {
                    if (hasJavaBin(d)) {
                        result.add("内置: " + d.toString());
                    }
                });
            } catch (Exception ignored) {
            }
        }
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) {
            result.add("JAVA_HOME: " + javaHome);
        }
        return result;
    }

    private static boolean hasJavaBin(Path dir) {
        return Files.exists(dir.resolve("bin/java.exe"))
                || Files.exists(dir.resolve("bin/java"));
    }

    // ------------------------------------------------------------------
    // DeepSeek（可选 AI 助手）
    // ------------------------------------------------------------------
    public static String deepseekChat(String apiKey, String userMessage) {
        if (apiKey == null || apiKey.isBlank()) {
            return "DeepSeek AI 未启用（未配置 API Key）";
        }
        try {
            ObjectNode body = JsonUtil.newObject();
            body.put("model", "deepseek-chat");
            var messages = body.putArray("messages");
            var sys = messages.addObject();
            sys.put("role", "system");
            sys.put("content", "你是一个 Minecraft 启动器助手，用简体中文简洁地回答用户问题。");
            var user = messages.addObject();
            user.put("role", "user");
            user.put("content", userMessage);
            body.put("stream", false);

            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.deepseek.com/v1/chat/completions"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            HttpResponse<String> resp = Downloader.CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return "DeepSeek 请求失败: HTTP " + resp.statusCode();
            }
            JsonNode data = JsonUtil.parse(resp.body());
            String content = data.get("choices").get(0).get("message").get("content").asText();
            return content.isBlank() ? "（无回复）" : content;
        } catch (Exception e) {
            return "与 DeepSeek 对话失败: " + e.getMessage();
        }
    }

    private Extras() {
    }
}
