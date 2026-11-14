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
    public static final String VERSION = "v5.1.0";

    public static final String REPO_OWNER = "BoscoWu";
    public static final String REPO_NAME = "Simple-Minecraft-Launcher-SCL-";

    /** GitHub 加速镜像（已剔除会跳广告站的 ghps.cc） */
    public static final String[] GH_MIRRORS = {
        "https://gh-proxy.com/",
        "https://ghproxy.net/",
        "https://ghfast.top/",
    };

    /** 黑名单镜像：无论如何都不使用 */
    public static final String[] MIRROR_BLACKLIST = { "ghps.cc" };

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

    private static Path resolveAppDir() {
        // jpackage 会设置 jpackage.app-path；普通 jar 用当前目录
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath != null && !appPath.isBlank()) {
            Path p = Paths.get(appPath).toAbsolutePath().getParent();
            if (p != null) return p;
        }
        // 从 jar 位置推断
        try {
            Path jar = Paths.get(Constants.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            if (jar.toString().endsWith(".jar")) {
                return jar.getParent();
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
