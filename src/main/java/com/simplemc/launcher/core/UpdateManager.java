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
     *
     * <p>按可靠性依次尝试，<b>任何一个拿到 tag_name 就返回</b>：</p>
     * <ol>
     *   <li>镜像 + api.github.com —— 数据最准（ungh.cc 有缓存，
     *       实测会落后好几个版本，不能当主渠道）</li>
     *   <li>直连 api.github.com</li>
     *   <li>ungh.cc —— 兜底，但要对缓存做校验</li>
     * </ol>
     */
    public static CheckResult check() {
        String apiPath = "https://api.github.com/repos/"
                + Constants.REPO_OWNER + "/" + Constants.REPO_NAME + "/releases/latest";

        // ---- 路径 1：镜像 + GitHub API（首选，数据实时）----
        for (String mirror : Constants.GH_MIRRORS_API) {
            try {
                JsonNode release = Http.fetchJson(mirror + apiPath,
                        Duration.ofSeconds(12), null);
                if (release != null && release.has("tag_name")) {
                    Log.info("更新检查来源: " + mirror);
                    return fromGithubRelease(release);
                }
            } catch (Exception e) {
                Log.warn("镜像 API 失败 " + mirror + ": " + shortMsg(e));
            }
        }

        // ---- 路径 2：直连 ----
        try {
            JsonNode release = Http.fetchJson(apiPath, Duration.ofSeconds(12), null);
            if (release != null && release.has("tag_name")) {
                Log.info("更新检查来源: 直连 api.github.com");
                return fromGithubRelease(release);
            }
        } catch (Exception e) {
            Log.warn("直连 API 失败: " + shortMsg(e));
        }

        // ---- 路径 3：ungh.cc（有缓存延迟，仅兜底）----
        try {
            CheckResult r = checkViaUngh();
            if (r != null) {
                Log.info("更新检查来源: ungh.cc（可能滞后）");
                return r;
            }
        } catch (Exception e) {
            Log.warn("ungh.cc 检查失败: " + shortMsg(e));
        }

        Log.warn("检查更新失败: 所有渠道均不可用");
        return new CheckResult(false, Constants.VERSION, null, null, 0,
                "检查更新失败（网络受限，可手动到 GitHub Releases 查看）");
    }

    /** 把异常压成一行，避免 403 的长 URL 刷屏 */
    private static String shortMsg(Exception e) {
        String m = e.getMessage();
        if (m == null) return e.getClass().getSimpleName();
        return m.length() > 80 ? m.substring(0, 80) + "..." : m;
    }

    /**
     * 通过 ungh.cc 检查更新。
     *
     * <p>返回 null 表示该渠道不可用，调用方继续试下一个。</p>
     */
    private static CheckResult checkViaUngh() throws Exception {
        String url = Constants.UNGH_API + "/" + Constants.REPO_OWNER
                + "/" + Constants.REPO_NAME + "/releases";

        // 用 Downloader 的共享 HttpClient（带超时、跟随重定向）
        String body = Http.getText(url, Duration.ofSeconds(12));
        if (body == null || body.isBlank()) return null;

        JsonNode root = Http.MAPPER.readTree(body);
        JsonNode releases = root.path("releases");
        if (!releases.isArray() || releases.size() == 0) return null;

        // 取第一个非 draft、非 prerelease 的
        JsonNode latest = null;
        for (JsonNode rel : releases) {
            if (rel.path("draft").asBoolean(false)) continue;
            if (rel.path("prerelease").asBoolean(false)) continue;
            latest = rel;
            break;
        }
        if (latest == null) latest = releases.path(0);

        String tag = latest.path("tag").asText("");
        if (tag.isBlank()) return null;

        if (compareVersions(tag, Constants.VERSION) <= 0) {
            return new CheckResult(false, tag, null, null, 0,
                    "当前已是最新版本 " + Constants.VERSION);
        }

        // 从 assets 里挑当前平台的
        String wantExt = isWindows() ? ".exe" : (isMac() ? ".dmg" : ".deb");
        JsonNode assets = latest.path("assets");
        String dl = null, name = null;
        long size = 0;
        if (assets.isArray()) {
            for (JsonNode a : assets) {
                String u = a.path("downloadUrl").asText("");
                if (u.toLowerCase().endsWith(wantExt)) {
                    dl = u;
                    name = u.substring(u.lastIndexOf('/') + 1);
                    size = a.path("size").asLong(0);
                    break;
                }
            }
        }
        if (dl == null) {
            return new CheckResult(false, tag, null, null, 0,
                    "发现新版本 " + tag + "，但没有适用于当前系统的安装包");
        }
        Log.info("发现新版本 " + tag + "（来源 ungh.cc）");
        return new CheckResult(true, tag, dl, name, size,
                "发现新版本 " + tag + "（当前 " + Constants.VERSION + "）");
    }

    /** 把 GitHub 的 release JSON 转成检查结果 */
    private static CheckResult fromGithubRelease(JsonNode release) {
        String tag = release.path("tag_name").asText("");
        if (tag.isBlank()) {
            return new CheckResult(false, Constants.VERSION, null, null, 0, "未获取到版本号");
        }
        if (compareVersions(tag, Constants.VERSION) <= 0) {
            return new CheckResult(false, tag, null, null, 0,
                    "当前已是最新版本 " + Constants.VERSION);
        }
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
     *
     * <p>实测各镜像对 releases/download 的支持不一致（gh-proxy 可能返回 403，
     * ghfast 正常），因此把全部候选地址交给 Downloader 依次尝试。</p>
     */
    public static Path download(CheckResult result, Consumer<String> stage,
                                Downloader.Progress progress) throws Exception {
        if (result.downloadUrl() == null) {
            throw new IllegalStateException("没有可用的下载地址");
        }
        Path dest = Constants.APP_DIR.resolve(result.assetName());
        stage.accept("下载更新 " + result.latestVersion() + "（"
                + Downloader.humanSize(result.size()) + "）...");

        String raw = result.downloadUrl();
        List<String> urls = new ArrayList<>();

        // 镜像优先（国内直连 GitHub 文件常失败）
        for (String m : Constants.GH_MIRRORS) {
            String full = m + raw;
            if (!isBad(full) && Http.isSafeUrl(full)) urls.add(full);
        }
        // 官方直连兜底
        if (Http.isSafeUrl(raw) && !urls.contains(raw)) urls.add(raw);

        if (urls.isEmpty()) {
            throw new IllegalStateException("没有安全的下载源可用");
        }
        Log.info("更新包候选源: " + urls.size() + " 个");

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
