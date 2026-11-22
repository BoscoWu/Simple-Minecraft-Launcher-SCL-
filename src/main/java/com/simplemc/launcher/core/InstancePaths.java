package com.simplemc.launcher.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.simplemc.launcher.util.Constants;
import com.simplemc.launcher.util.Log;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 实例目录与内容子目录（版本隔离）。
 *
 * <p>Minecraft 支持两种布局：</p>
 * <ul>
 *   <li><b>不隔离</b>：所有版本共用 {@code .minecraft/mods}、{@code .minecraft/resourcepacks}…</li>
 *   <li><b>隔离</b>：每个版本目录下自带 {@code mods}、{@code resourcepacks}…</li>
 * </ul>
 *
 * <p>启动器统一采用隔离布局：内容放进
 * {@code .minecraft/versions/<版本ID>/<子目录>}，
 * 这样不同版本装了不同模组也不会互相干扰。</p>
 */
public final class InstancePaths {

    /** 允许的内容子目录名 */
    public static final String MODS = "mods";
    public static final String RESOURCEPACKS = "resourcepacks";
    public static final String SHADERPACKS = "shaderpacks";
    public static final String DATAPACKS = "datapacks";
    public static final String CONFIG = "config";
    public static final String SAVES = "saves";

    private InstancePaths() {}

    /** 版本的独立目录：{@code .minecraft/versions/<id>} */
    public static Path versionDir(String versionId) {
        return Constants.MC_DIR.resolve("versions").resolve(versionId);
    }

    /**
     * 某个版本的内容目录（隔离布局）。
     *
     * <p>若版本目录存在，返回其下的子目录；否则退回
     * {@code .minecraft/<sub>}，避免往不存在的版本里写东西。</p>
     */
    public static Path contentDir(String versionId, String sub) {
        if (versionId != null && !versionId.isBlank()) {
            Path vdir = versionDir(versionId);
            if (Files.isDirectory(vdir)) {
                return vdir.resolve(sub);
            }
        }
        return Constants.MC_DIR.resolve(sub);
    }

    /**
     * 准备内容目录：建目录并返回路径。
     */
    public static Path ensureContentDir(String versionId, String sub) throws Exception {
        Path dir = contentDir(versionId, sub);
        Files.createDirectories(dir);
        return dir;
    }

    /**
     * 判断版本是否采用隔离布局。
     *
     * <p>依据：版本目录下是否已经有内容子目录（比如 mods）。</p>
     */
    public static boolean isIsolated(String versionId) {
        if (versionId == null || versionId.isBlank()) return false;
        Path vdir = versionDir(versionId);
        if (!Files.isDirectory(vdir)) return false;
        for (String sub : new String[]{MODS, RESOURCEPACKS, SHADERPACKS}) {
            if (Files.isDirectory(vdir.resolve(sub))) return true;
        }
        return false;
    }

    /**
     * 定义一个版本要下载的「游戏版本」。
     *
     * <p>加载器实例（如 {@code fabric-loader-0.20.1-1.21.1}）的实际 MC 版本
     * 要从 {@code inheritsFrom} 取。</p>
     */
    public static String baseVersionOf(String versionId) {
        try {
            return new InstanceManager(Constants.MC_DIR).resolveBaseVersion(versionId);
        } catch (Exception e) {
            return versionId;
        }
    }

    /**
     * 判断版本使用哪个加载器（用于 Modrinth 过滤）。
     *
     * @return fabric / quilt / forge / neoforge / 空串（原版）
     */
    public static String loaderOf(String versionId) {
        try {
            JsonNode j = new VersionManager(null).readVersionJson(versionId);
            String id = j.path("id").asText("").toLowerCase();
            String main = j.path("mainClass").asText("").toLowerCase();
            String all = id + " " + main;
            if (all.contains("quilt")) return "quilt";
            if (all.contains("fabric")) return "fabric";
            if (all.contains("neoforge")) return "neoforge";
            if (all.contains("forge")) return "forge";
        } catch (Exception e) {
            Log.warn("读取加载器失败 " + versionId + ": " + e.getMessage());
        }
        return "";
    }

    /** 子目录的中文名，用于提示 */
    public static String subDisplayName(String sub) {
        return switch (sub) {
            case MODS -> "mods（模组）";
            case RESOURCEPACKS -> "resourcepacks（资源包）";
            case SHADERPACKS -> "shaderpacks（光影包）";
            case DATAPACKS -> "datapacks（数据包）";
            case SAVES -> "saves（世界存档）";
            default -> sub;
        };
    }
}
