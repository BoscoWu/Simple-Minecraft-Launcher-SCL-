package com.simplemc.launcher.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 代理设置（供下载与外部安装器共用）。
 *
 * <p>用户在设置里填的代理会被转换成 JVM 系统属性，
 * 这样 {@code HttpURLConnection}（NeoForge/Forge 安装器内部用的就是它）
 * 与 {@link Downloader} 都能自动生效。</p>
 *
 * <p>支持格式：{@code http://127.0.0.1:7890}、
 * {@code 127.0.0.1:7890}、{@code socks5://127.0.0.1:1080}</p>
 */
public final class ProxyUtil {

    private static volatile String current = "";

    private ProxyUtil() {}

    /** 从配置读取并应用代理设置 */
    public static void applyFromConfig(Config config) {
        apply(config.getString("proxy"));
    }

    /** 应用代理；传空串表示不使用代理 */
    public static void apply(String proxy) {
        String p = proxy == null ? "" : proxy.trim();
        current = p;

        // 先清掉之前设过的
        System.clearProperty("http.proxyHost");
        System.clearProperty("http.proxyPort");
        System.clearProperty("https.proxyHost");
        System.clearProperty("https.proxyPort");
        System.clearProperty("socksProxyHost");
        System.clearProperty("socksProxyPort");

        if (p.isEmpty()) {
            Log.info("未使用代理");
            return;
        }

        try {
            String scheme = "http";
            String hostPort = p;
            int idx = p.indexOf("://");
            if (idx > 0) {
                scheme = p.substring(0, idx).toLowerCase();
                hostPort = p.substring(idx + 3);
            }
            // 去掉尾部路径
            int slash = hostPort.indexOf('/');
            if (slash > 0) hostPort = hostPort.substring(0, slash);

            String host;
            int port;
            int colon = hostPort.lastIndexOf(':');
            if (colon > 0) {
                host = hostPort.substring(0, colon);
                port = Integer.parseInt(hostPort.substring(colon + 1));
            } else {
                host = hostPort;
                port = "socks5".equals(scheme) || "socks".equals(scheme) ? 1080 : 8080;
            }

            if ("socks5".equals(scheme) || "socks".equals(scheme)) {
                System.setProperty("socksProxyHost", host);
                System.setProperty("socksProxyPort", String.valueOf(port));
            } else {
                System.setProperty("http.proxyHost", host);
                System.setProperty("http.proxyPort", String.valueOf(port));
                System.setProperty("https.proxyHost", host);
                System.setProperty("https.proxyPort", String.valueOf(port));
            }
            Log.info("已应用代理: " + scheme + "://" + host + ":" + port);
        } catch (Exception e) {
            Log.warn("代理格式不正确（应形如 http://127.0.0.1:7890）: " + p);
            current = "";
        }
    }

    public static String current() {
        return current;
    }

    public static boolean isEnabled() {
        return !current.isEmpty();
    }

    /**
     * 生成给外部进程用的 {@code -D} 参数。
     *
     * <p>安装器是独立 JVM，运行时要显式传代理参数。</p>
     */
    public static List<String> jvmArgs() {
        List<String> args = new ArrayList<>();
        if (!isEnabled()) return args;
        for (String key : new String[]{
                "http.proxyHost", "http.proxyPort",
                "https.proxyHost", "https.proxyPort",
                "socksProxyHost", "socksProxyPort"}) {
            String v = System.getProperty(key);
            if (v != null && !v.isBlank()) {
                args.add("-D" + key + "=" + v);
            }
        }
        return args;
    }
}
