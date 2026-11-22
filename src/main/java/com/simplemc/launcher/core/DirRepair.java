package com.simplemc.launcher.core;

import com.simplemc.launcher.util.Constants;
import com.simplemc.launcher.util.Log;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 游戏目录完整性自检。
 *
 * <p>启动器启动时检查 {@code .minecraft} 的关键子目录，
 * 发现缺失就自动补建，避免用户误删后各种奇怪报错
 * （比如删掉 mods 后装的模组"消失"、删掉 assets 后游戏黑屏）。</p>
 *
 * <p>只建空目录，不会往里面放文件，也不会覆盖已存在的内容。</p>
 */
public final class DirRepair {

    /**
     * {@code .minecraft} 下必备的子目录。
     *
     * <p>顺序按重要性排，日志里按这个顺序输出。</p>
     */
    private static final String[][] REQUIRED = {
            {"versions",        "版本目录（缺失会导致所有已装版本不可见）"},
            {"libraries",       "依赖库（缺失会导致游戏无法启动）"},
            {"assets",          "游戏资源（缺失会导致黑屏、无音效）"},
            {"logs",            "日志目录"},
            {"mods",            "模组目录"},
            {"resourcepacks",   "资源包目录"},
            {"shaderpacks",     "光影包目录"},
            {"saves",           "世界存档目录"},
            {"screenshots",     "截图目录"},
            {"config",          "配置目录"},
    };

    private DirRepair() {}

    /** 修复结果 */
    public record Result(boolean mcDirCreated, List<String> created, List<String> existed,
                         boolean recycleHint) {
        public boolean hasChanges() {
            return mcDirCreated || !created.isEmpty();
        }
    }

    /**
     * 检查并补全目录。
     */
    public static Result checkAndRepair() {
        List<String> created = new ArrayList<>();
        List<String> existed = new ArrayList<>();
        boolean mcDirCreated = false;
        boolean recycleHint = false;

        try {
            // 1. .minecraft 本身
            boolean mcMissing = !Files.isDirectory(Constants.MC_DIR);
            if (mcMissing) {
                Files.createDirectories(Constants.MC_DIR);
                mcDirCreated = true;
                Log.warn("检测到 .minecraft 目录缺失，已重新创建: " + Constants.MC_DIR);
                recycleHint = true;
            }

            // 2. 各子目录
            for (String[] item : REQUIRED) {
                String name = item[0];
                Path dir = Constants.MC_DIR.resolve(name);
                if (Files.isDirectory(dir)) {
                    existed.add(name);
                } else {
                    Files.createDirectories(dir);
                    created.add(name);
                    Log.warn("补全缺失目录: " + name + "（" + item[1] + "）");
                    // 空的 versions / libraries / assets 缺失通常意味着内容被删
                    if ("versions".equals(name) || "libraries".equals(name)
                            || "assets".equals(name)) {
                        recycleHint = true;
                    }
                }
            }

            // 3. 内容子目录已补，但 Forge/NeoForge 安装器还需要 launcher_profiles.json
            ensureLauncherProfiles();

            if (!created.isEmpty() || mcDirCreated) {
                Log.info("目录自检完成：新建 " + created.size() + " 个目录"
                        + (mcDirCreated ? "（含 .minecraft）" : ""));
            } else {
                Log.info("目录自检通过：所有必要目录均存在");
            }

            if (recycleHint) {
                Log.warn("提示：如果这些目录是被误删的，可打开Windows回收站还原，"
                        + "游戏数据通常能完整找回");
            }
        } catch (Exception e) {
            Log.error("目录自检失败", e);
        }

        return new Result(mcDirCreated, created, existed, recycleHint);
    }

    /** 供外部查询：某个目录是否存在 */
    public static boolean exists(String sub) {
        return Files.isDirectory(Constants.MC_DIR.resolve(sub));
    }

    private static void ensureLauncherProfiles() {
        try {
            Path file = Constants.MC_DIR.resolve("launcher_profiles.json");
            if (Files.isRegularFile(file) && Files.size(file) > 10) {
                try {
                    com.simplemc.launcher.util.Http.MAPPER.readTree(
                            Files.readAllBytes(file));
                    return;
                } catch (Exception ignored) {
                    // 损坏，重建
                }
            }
            Files.writeString(file, "{\n"
                    + "  \"profiles\": {},\n"
                    + "  \"settings\": {\"enableSnapshots\": false,"
                    + " \"enableAdvanced\": true, \"showMenu\": false},\n"
                    + "  \"version\": 3\n}\n",
                    java.nio.charset.StandardCharsets.UTF_8);
            Log.info("已补全 launcher_profiles.json");
        } catch (Exception e) {
            Log.warn("补全 launcher_profiles.json 失败: " + e.getMessage());
        }
    }
}
