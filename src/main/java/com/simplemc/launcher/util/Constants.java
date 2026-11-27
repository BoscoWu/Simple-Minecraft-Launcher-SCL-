package com.simplemc.launcher.util;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 全局常量与路径。
 */
public final class Constants {

    private Constants() {}

    public static final String APP_NAME = "极简 MC 启动器";
    public static final String APP_TITLE = "极简 MC 启动器";
    public static final String VERSION = "v5.2.2";

    public static final String REPO_OWNER = "BoscoWu";
    public static final String REPO_NAME = "Simple-Minecraft-Launcher-SCL-";

    /**
     * GitHub 加速镜像（已剔除会跳广告站的 ghps.cc）。
     *
     * <p>顺序按 2026-11 实测的 releases/download 可用性排列：
     * ghfast.top 最快且稳定，ghproxy.net 慢但可用，
     * gh-proxy.com 对 download 路径返回 403 所以排最后作兜底。</p>
     */
    public static final String[] GH_MIRRORS = {
        "https://ghfast.top/",
        "https://ghproxy.net/",
        "https://gh-proxy.com/",
    };

    /** 黑名单镜像：无论如何都不使用 */
    public static final String[] MIRROR_BLACKLIST = { "ghps.cc" };

    /** 更新检查用的第三方 GitHub 数据代理（绕过 api.github.com 被阻断/403 的问题） */
    public static final String UNGH_API = "https://ungh.cc/repos";

    public static final String USER_AGENT = "SimpleMCLauncher/" + VERSION;

    /** Microsoft OAuth（Minecraft 正版登录） */
    public static final String MS_CLIENT_ID = "8d7c60d0-fddf-464c-a9f4-d190f1daa576";
    public static final String MS_SCOPE = "XboxLive.signin offline_access";

    /** 数据目录：程序所在目录下的 pyLauncher（与 Python 版保持一致，方便共用数据） */
    public static final Path APP_DIR = resolveAppDir();
    public static final Path DATA_DIR = APP_DIR.resolve("pyLauncher");
    public static final Path MC_DIR = DATA_DIR.resolve(".minecraft");
    public static final Path SERVER_DIR = DATA_DIR.resolve("servers");
    public static final Path JAVA_DIR = DATA_DIR.resolve("java");
    public static final Path CONFIG_FILE = DATA_DIR.resolve("launcher_config.json");
    public static final Path LOG_FILE = DATA_DIR.resolve("launcher.log");

    /**
     * 解析应用根目录（便携版的顶层目录，pyLauncher 就放这里）。
     *
     * <p>jpackage 便携版的布局是：</p>
     * <pre>
     * SimpleMCLauncher/
     * ├── SimpleMCLauncher.exe     <- jpackage.app-path 指向它
     * ├── app/                     <- jar 在这里
     * ├── runtime/                 <- 内置 Java
     * └── pyLauncher/              <- 数据目录（我们要找的位置）
     * </pre>
     *
     * <p>{@code jpackage.app-path} 是 {@code <root>/app/SimpleMCLauncher.exe}，
     * 所以数据目录应该取它的<b>上两级</b>，而不是同级。</p>
     */
    private static Path resolveAppDir() {
        // 1. 允许外部覆盖（调试时方便）
        String override = System.getProperty("scl.app.dir");
        if (override != null && !override.isBlank()) {
            return Paths.get(override).toAbsolutePath();
        }

        // 2. jpackage 便携版：<root>/app/SimpleMCLauncher.exe -> 取上两级
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath != null && !appPath.isBlank()) {
            try {
                Path exeDir = Paths.get(appPath).toAbsolutePath().getParent();
                if (exeDir != null) {
                    if ("app".equalsIgnoreCase(exeDir.getFileName().toString())
                            && exeDir.getParent() != null) {
                        return exeDir.getParent();
                    }
                    return exeDir;
                }
            } catch (Exception ignored) {
            }
        }

        // 3. 从 jar 位置推断（从源码/普通 jar 运行时）
        try {
            Path jar = Paths.get(Constants.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            if (jar.toString().endsWith(".jar")) {
                Path dir = jar.getParent();
                // target/SimpleMCLauncher.jar -> 回到项目根
                if (dir != null && "target".equals(dir.getFileName().toString())
                        && dir.getParent() != null) {
                    return dir.getParent();
                }
                return dir;
            }
        } catch (Exception ignored) {
        }

        return Paths.get(System.getProperty("user.dir")).toAbsolutePath();
    }

    /** 确保数据目录存在 */
    public static void ensureDirectories() {
        try {
            java.nio.file.Files.createDirectories(DATA_DIR);
            java.nio.file.Files.createDirectories(MC_DIR);
            java.nio.file.Files.createDirectories(SERVER_DIR);
            java.nio.file.Files.createDirectories(JAVA_DIR);
        } catch (Exception e) {
            System.err.println("创建目录失败: " + e.getMessage());
        }
    }

    // ---------------- 平台与外观 ----------------

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    public static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    public static boolean isLinux() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("linux") || os.contains("unix");
    }

    /**
     * 是否为 Windows 11 及以上。
     *
     * <p>Windows NT 版本号：Win10 为 10.0.x 且内部版本号 &lt; 22000，
     * Win11 从内部版本号 22000 开始。</p>
     */
    public static boolean isWindows11OrLater() {
        if (!isWindows()) return false;
        try {
            String build = System.getProperty("os.build");
            if (build == null) build = System.getenv("OS_BUILD");
            // Java 没有直接的 build number，用命令行查询一次
            Process p = new ProcessBuilder("cmd", "/c", "ver")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(),
                    java.nio.charset.Charset.defaultCharset()).trim();
            p.waitFor();
            // 形如 "Microsoft Windows [Version 10.0.26100.2314]"
            var m = java.util.regex.Pattern
                    .compile("10\\.0\\.(\\d+)").matcher(out);
            if (m.find()) {
                int buildNo = Integer.parseInt(m.group(1));
                return buildNo >= 22000;
            }
        } catch (Exception ignored) {
        }
        // 拿不到版本号时保守按 Win10 处理（直角）
        return false;
    }

    /**
     * 是否使用圆角界面。
     * Windows 10 → 直角；Windows 11 / macOS / Linux → 圆角。
     */
    public static boolean useRoundedCorners() {
        if (isWindows()) return isWindows11OrLater();
        return true;
    }
}
