package com.simplemc.launcher.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.simplemc.launcher.util.Constants;
import com.simplemc.launcher.util.Downloader;
import com.simplemc.launcher.util.Http;
import com.simplemc.launcher.util.Log;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 启动器自更新：检查版本、下载新包、替换并重启。
 */
public final class UpdateManager {

    /** 检查结果 */
    public record CheckResult(boolean hasUpdate, String latestVersion,
                              String downloadUrl, String assetName,
                              long size, String message) {}

    private UpdateManager() {}

    /** 需要排除的镜像（会跳广告站的） */
    private static boolean isBad(String url) {
        for (String b : Constants.MIRROR_BLACKLIST) {
            if (url.contains(b)) return true;
        }
        return false;
    }

    /**
     * 检查更新。
     */
    public static CheckResult check() {
        String apiPath = "https://api.github.com/repos/"
                + Constants.REPO_OWNER + "/" + Constants.REPO_NAME + "/releases/latest";

        JsonNode release = null;
        Exception last = null;
        for (String mirror : Constants.GH_MIRRORS) {
            try {
                release = Http.fetchJson(mirror + apiPath, Duration.ofSeconds(12), null);
                if (release != null && release.has("tag_name")) break;
            } catch (Exception e) {
                last = e;
                Log.warn("检查更新失败 " + mirror + ": " + e.getMessage());
            }
        }
        if (release == null) {
            // 直连兜底
            try {
                release = Http.fetchJson(apiPath, Duration.ofSeconds(12), null);
            } catch (Exception e) {
                last = e;
            }
        }
        if (release == null) {
            return new CheckResult(false, Constants.VERSION, null, null, 0,
                    "检查更新失败: " + (last == null ? "未知错误" : last.getMessage()));
        }

        String tag = release.path("tag_name").asText("");
        if (tag.isBlank()) {
            return new CheckResult(false, Constants.VERSION, null, null, 0, "未获取到版本号");
        }

        if (compareVersions(tag, Constants.VERSION) <= 0) {
            return new CheckResult(false, tag, null, null, 0,
                    "当前已是最新版本 " + Constants.VERSION);
        }

        // 找对应平台的资产
        String wantExt = isWindows() ? ".exe" : (isMac() ? ".dmg" : ".deb");
        JsonNode assets = release.path("assets");
        String url = null, name = null;
        long size = 0;
        if (assets.isArray()) {
            for (JsonNode a : assets) {
                String n = a.path("name").asText("").toLowerCase();
                if (n.endsWith(wantExt)) {
                    url = a.path("browser_download_url").asText("");
                    name = a.path("name").asText("");
                    size = a.path("size").asLong(0);
                    break;
                }
            }
        }
        if (url == null || url.isBlank()) {
            return new CheckResult(false, tag, null, null, 0,
                    "发现新版本 " + tag + "，但没有适用于当前系统的安装包");
        }
        return new CheckResult(true, tag, url, name, size,
                "发现新版本 " + tag + "（当前 " + Constants.VERSION + "）");
    }

    /**
     * 下载更新包。
     */
    public static Path download(CheckResult result, Consumer<String> stage,
                                Downloader.Progress progress) throws Exception {
        if (result.downloadUrl() == null) {
            throw new IllegalStateException("没有可用的下载地址");
        }
        Path dest = Constants.APP_DIR.resolve(result.assetName());
        stage.accept("下载更新 " + result.latestVersion() + "（"
                + Downloader.humanSize(result.size()) + "）...");

        // 组装镜像地址（过滤坏镜像）
        List<String> urls = new ArrayList<>();
        for (String m : Constants.GH_MIRRORS) {
            String full = m + result.downloadUrl();
            if (!isBad(full)) urls.add(full);
        }
        // 只使用安全地址（白名单内）
        urls.removeIf(u -> !Http.isSafeUrl(u));
        if (urls.isEmpty()) {
            throw new IllegalStateException("没有安全的下载源可用");
        }

        Downloader.download(urls, dest, progress, null);
        Log.info("更新包已下载: " + dest);
        return dest;
    }

    /**
     * 应用更新并重启（Windows）。
     *
     * @param newFile 下载好的新版本 exe
     */
    public static void applyAndRestart(Path newFile) throws Exception {
        if (!isWindows()) {
            throw new IllegalStateException("当前平台为手动更新：请用新文件替换旧版本");
        }
        Path current = currentExe();
        if (current == null) {
            throw new IllegalStateException("无法定位当前程序位置");
        }
        Path staged = current.resolveSibling("_update_new.exe");
        Files.deleteIfExists(staged);
        Files.move(newFile, staged, StandardCopyOption.REPLACE_EXISTING);

        long pid = ProcessHandle.current().pid();
        Path bat = current.resolveSibling("_apply_update.bat");

        String script = "@echo off\r\n"
                + "chcp 65001 >nul\r\n"
                + ":wait\r\n"
                + "tasklist /FI \"PID eq " + pid + "\" 2>nul | find \"" + pid + "\" >nul\r\n"
                + "if not errorlevel 1 (\r\n"
                + "  timeout /t 1 /nobreak >nul\r\n"
                + "  goto wait\r\n"
                + ")\r\n"
                + "move /Y \"" + staged.toAbsolutePath() + "\" \""
                + current.toAbsolutePath() + "\"\r\n"
                + "start \"\" \"" + current.toAbsolutePath() + "\"\r\n"
                + "del \"%~f0\"\r\n";

        Files.writeString(bat, script, java.nio.charset.StandardCharsets.UTF_8);
        Log.info("已生成更新脚本，即将重启");

        new ProcessBuilder("cmd", "/c", bat.toAbsolutePath().toString())
                .directory(Constants.APP_DIR.toFile())
                .start();
    }

    private static Path currentExe() {
        try {
            String cmd = System.getProperty("sun.java.command", "");
            // jpackage 下 exe 就是启动器的可执行文件
            Path exe = Constants.APP_DIR.resolve("SimpleMCLauncher.exe");
            if (Files.isRegularFile(exe)) return exe;
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 版本比较：返回 >0 表示 a 比 b 新 */
    public static int compareVersions(String a, String b) {
        String va = a.replaceAll("^[vV]", "").trim();
        String vb = b.replaceAll("^[vV]", "").trim();
        String[] pa = va.split("[.\\-+]");
        String[] pb = vb.split("[.\\-+]");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            String sa = i < pa.length ? pa[i].replaceAll("\\D.*", "") : "0";
            String sb = i < pb.length ? pb[i].replaceAll("\\D.*", "") : "0";
            int x = sa.isEmpty() ? 0 : Integer.parseInt(sa);
            int y = sb.isEmpty() ? 0 : Integer.parseInt(sb);
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }
}
