package com.simplemc.launcher.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 版本号与加载器相关工具。
 */
public final class VersionUtil {

    private static final Pattern NUM = Pattern.compile("\\d+");

    /**
     * 根据版本 id 推断加载器类型（兼容新旧两种命名）。
     */
    public static String detectLoader(String versionId) {
        if (versionId.endsWith("-neoforge")) {
            return "neoforge";
        }
        if (versionId.endsWith("-fabric")) {
            return "fabric";
        }
        if (versionId.endsWith("-quilt")) {
            return "quilt";
        }
        if (versionId.endsWith("-forge")) {
            return "forge";
        }
        if (versionId.startsWith("fabric-loader")) {
            return "fabric";
        }
        if (versionId.startsWith("quilt-loader")) {
            return "quilt";
        }
        if (versionId.startsWith("neoforge")) {
            return "neoforge";
        }
        if (versionId.contains("-forge-")) {
            return "forge";
        }
        return "vanilla";
    }

    /**
     * 比较两个版本号，返回 1 / 0 / -1。
     */
    public static int compareVersions(String v1, String v2) {
        String[] a = v1.split("[.\\-+]");
        String[] b = v2.split("[.\\-+]");
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int x = i < a.length ? parseNum(a[i]) : 0;
            int y = i < b.length ? parseNum(b[i]) : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    private static int parseNum(String s) {
        Matcher m = NUM.matcher(s);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private VersionUtil() {
    }
}
