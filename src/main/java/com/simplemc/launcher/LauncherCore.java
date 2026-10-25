package com.simplemc.launcher;

import com.simplemc.launcher.auth.MicrosoftAuth;
import com.simplemc.launcher.minecraft.MinecraftManager;
import com.simplemc.launcher.minecraft.ModLoaderManager;
import com.simplemc.launcher.mod.ModManager;
import com.simplemc.launcher.server.ServerManager;
import com.simplemc.launcher.util.AppPaths;
import com.simplemc.launcher.util.Config;
import com.simplemc.launcher.util.Log;
import com.simplemc.launcher.util.VersionUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class LauncherCore {

    public interface Callbacks {
        void log(String message);
        void status(String message);
        void progress(long current, long total);
        void serverLog(String message);
    }

    private final Callbacks callbacks;
    private final Config config;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        return t;
    });

    private final MinecraftManager minecraftManager;
    private final ModLoaderManager modLoaderManager;
    private final ModManager modManager;
    private final ServerManager serverManager;

    private Process gameProcess;
    
    // 下载控制
    private Future<?> currentDownloadTask;
    private final AtomicBoolean cancelDownloadFlag = new AtomicBoolean(false);
    private String downloadingVersion = null; // 记录当前正在下载的版本名，方便取消时清理

    public LauncherCore(Callbacks callbacks) {
        this.callbacks = callbacks;
        Log.init(AppPaths.LOG_FILE);
        try {
            Files.createDirectories(AppPaths.PYLAUNCHER_DIR);
            Files.createDirectories(AppPaths.MC_DIR);
            Files.createDirectories(AppPaths.SERVER_DIR);
            Files.createDirectories(AppPaths.JAVA_DIR);
        } catch (IOException e) {
            Log.error("创建目录失败", e);
        }
        this.config = Config.load(AppPaths.CONFIG_FILE);
        this.minecraftManager = new MinecraftManager(AppPaths.MC_DIR, callbacks::log,
                callbacks::status, callbacks::progress);
        this.modLoaderManager = new ModLoaderManager(AppPaths.MC_DIR, callbacks::log,
                callbacks::status, callbacks::progress);
        this.modManager = new ModManager(AppPaths.MC_DIR, callbacks::log, callbacks::status,
                callbacks::progress, modLoaderManager);
        this.serverManager = new ServerManager(AppPaths.SERVER_DIR, callbacks::log,
                callbacks::status, callbacks::progress);
    }

    public void shutdown() {
        stopGame();
        cancelDownload();
        serverManager.stopServer();
        executor.shutdownNow();
    }

    public Config config() {
        return config;
    }

    private void saveConfig() {
        config.save(AppPaths.CONFIG_FILE);
    }

    private void runAsync(Runnable r) {
        executor.submit(r);
    }

    // ------------------------------------------------------------------
    // 账号
    // ------------------------------------------------------------------
    public boolean isLoggedIn() {
        return !config.getString("ms_refresh_token").isEmpty();
    }

    public String playerDisplay() {
        if (isLoggedIn()) {
            return config.getString("ms_username", "已登录用户");
        }
        return config.getString("player_name", "Player");
    }

    public void setPlayerName(String name) {
        String n = name == null ? "" : name.trim();
        if (!n.isEmpty()) {
            config.put("player_name", n);
            saveConfig();
            callbacks.log("离线玩家名已设置为: " + n);
        }
    }

    public void login() {
        runAsync(() -> {
            try {
                MicrosoftAuth.Account acc = MicrosoftAuth.deviceLogin(callbacks::log);
                if (acc != null) {
                    config.put("ms_refresh_token", acc.refreshToken());
                    config.put("ms_username", acc.username());
                    config.put("ms_uuid", acc.uuid());
                    config.put("ms_access_token", acc.accessToken());
                    saveConfig();
                }
            } catch (Exception e) {
                callbacks.log("登录失败: " + e.getMessage());
                Log.error("登录失败", e);
            }
        });
    }

    public MicrosoftAuth.DeviceCode requestDeviceCode() throws Exception {
        return MicrosoftAuth.requestDeviceCode();
    }

    public void pollDeviceLogin(MicrosoftAuth.DeviceCode dc, Runnable onSuccess) {
        runAsync(() -> {
            try {
                MicrosoftAuth.Account acc = MicrosoftAuth.pollDeviceCode(dc, callbacks::log);
                if (acc != null) {
                    config.put("ms_refresh_token", acc.refreshToken());
                    config.put("ms_username", acc.username());
                    config.put("ms_uuid", acc.uuid());
                    config.put("ms_access_token", acc.accessToken());
                    saveConfig();
                    onSuccess.run();
                }
            } catch (Exception e) {
                callbacks.log("登录失败: " + e.getMessage());
                Log.error("登录失败", e);
            }
        });
    }

    public void logout() {
        config.putNull("ms_refresh_token");
        config.putNull("ms_username");
        config.putNull("ms_uuid");
        config.putNull("ms_access_token");
        saveConfig();
        callbacks.log("已退出登录");
    }

    private MicrosoftAuth.Account getLoginOptions() {
        String refresh = config.getString("ms_refresh_token");
        if (!refresh.isEmpty()) {
            try {
                MicrosoftAuth.Account acc = MicrosoftAuth.refresh(refresh);
                if (acc.refreshToken() != null && !acc.refreshToken().isEmpty()) {
                    config.put("ms_refresh_token", acc.refreshToken());
                }
                config.put("ms_username", acc.username());
                config.put("ms_uuid", acc.uuid());
                config.put("ms_access_token", acc.accessToken());
                saveConfig();
                return acc;
            } catch (Exception e) {
                Log.warn("刷新微软令牌失败: " + e.getMessage());
                return null;
            }
        }
        // 离线模式：生成离线 UUID
        String playerName = config.getString("player_name", "Player");
        String offlineUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes(StandardCharsets.UTF_8))
                .toString().replace("-", "");
        return new MicrosoftAuth.Account(playerName, offlineUuid, "", "");
    }

    // ------------------------------------------------------------------
    // 版本
    // ------------------------------------------------------------------
    public List<String> installedVersions() {
        return minecraftManager.installedVersions();
    }

    public void installVanilla(String version) {
        if (currentDownloadTask != null && !currentDownloadTask.isDone()) {
            callbacks.log("已有下载任务正在进行，请先取消");
            return;
        }
        cancelDownloadFlag.set(false);
        downloadingVersion = version;
        currentDownloadTask = executor.submit(() -> {
            try {
                minecraftManager.installVanilla(version, cancelDownloadFlag);
                if (cancelDownloadFlag.get()) {
                    callbacks.log("下载已取消");
                    callbacks.status("下载已取消");
                } else {
                    config.put("current_version", version);
                    config.put("current_loader", "vanilla");
                    config.put("original_version", version);
                    saveConfig();
                    callbacks.log("原版 Minecraft " + version + " 安装完成！");
                    callbacks.status("安装完成");
                }
            } catch (Exception e) {
                callbacks.log("安装失败: " + e.getMessage());
                callbacks.status("安装失败");
                Log.error("安装 Minecraft 失败", e);
            } finally {
                currentDownloadTask = null;
                downloadingVersion = null;
            }
        });
    }

    public void installLoader(String loader, String version) {
        if (currentDownloadTask != null && !currentDownloadTask.isDone()) {
            callbacks.log("已有下载任务正在进行，请先取消");
            return;
        }
        cancelDownloadFlag.set(false);
        downloadingVersion = version + "-" + loader; // 安装器生成的名字可能会有点区别，但主版本名是 version
        currentDownloadTask = executor.submit(() -> {
            try {
                minecraftManager.installVanilla(version, cancelDownloadFlag);
                if (cancelDownloadFlag.get()) {
                    callbacks.log("下载已取消");
                    callbacks.status("下载已取消");
                    return;
                }
                String installedId = modLoaderManager.install(loader, version, cancelDownloadFlag);
                if (cancelDownloadFlag.get()) {
                    callbacks.log("下载已取消");
                    callbacks.status("下载已取消");
                    return;
                }
                String finalId = version + "-" + loader;
                minecraftManager.renameVersion(installedId, finalId);
                config.put("current_version", finalId);
                config.put("current_loader", loader);
                config.put("original_version", version);
                saveConfig();
                if ("fabric".equals(loader)) {
                    modManager.installFabricApi(version, finalId);
                }
                callbacks.log(loader + " + Minecraft " + version + " 安装完成！版本: " + finalId);
                callbacks.status("安装完成");
            } catch (Exception e) {
                callbacks.log("安装失败: " + e.getMessage());
                callbacks.status("安装失败");
                Log.error("安装加载器失败", e);
            } finally {
                currentDownloadTask = null;
                downloadingVersion = null;
            }
        });
    }

    public void cancelDownload() {
        cancelDownloadFlag.set(true);
        if (currentDownloadTask != null) {
            callbacks.log("已发送取消下载指令，等待当前文件块完成...");
            // 清理残留文件
            cleanUpDownloadedFiles(downloadingVersion);
        }
    }

    // 清理下载残留
    private void cleanUpDownloadedFiles(String version) {
        if (version == null || version.isBlank()) return;
        
        try {
            // 先尝试清理带 loader 的版本（例如 1.21.1-fabric）
            Path versionDir = AppPaths.MC_DIR.resolve("versions").resolve(version);
            deleteDirectory(versionDir);
            
            // 有时安装器生成了别的名字（比如 fabric-loader-0.15.0-1.21.1），可以通过遍历来清理所有包含该版本的文件夹
            Path versionsDir = AppPaths.MC_DIR.resolve("versions");
            if (Files.isDirectory(versionsDir)) {
                try (Stream<Path> stream = Files.list(versionsDir)) {
                    List<Path> dirs = stream.filter(Files::isDirectory).toList();
                    for (Path dir : dirs) {
                        String dirName = dir.getFileName().toString();
                        // 如果目录名包含了我们要下载的版本号，就删除
                        if (dirName.contains(version) || dirName.equals(version) || dirName.equals(version + "-fabric") || dirName.equals(version + "-forge")) {
                            deleteDirectory(dir);
                        }
                    }
                }
            }
        } catch (Exception e) {
            callbacks.log("清理残留文件失败: " + e.getMessage());
        }
    }

    // 递归删除目录
    private void deleteDirectory(Path path) {
        try {
            if (Files.exists(path)) {
                try (Stream<Path> walk = Files.walk(path)) {
                    walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.delete(p);
                            } catch (IOException ignored) {
                            }
                        });
                }
            }
        } catch (IOException ignored) {
        }
    }

    // ------------------------------------------------------------------
    // 启动
    // ------------------------------------------------------------------
    public void launch(String version) {
        runAsync(() -> {
            try {
                MicrosoftAuth.Account acc = getLoginOptions();
                if (acc == null) {
                    callbacks.log("登录已失效，请重新登录");
                    return;
                }
                String loader = VersionUtil.detectLoader(version);
                config.put("current_version", version);
                config.put("current_loader", loader);
                saveConfig();
                callbacks.status("正在启动游戏...");
                callbacks.log("正在启动游戏: " + version + " ...");
                Process p = minecraftManager.launch(version, acc.username(), acc.uuid(), acc.accessToken(),
                        config.getString("max_memory", "2G"), config.getString("min_memory", "1G"));
                gameProcess = p;
                // 游戏日志剥离出下载框
                readProcessLog(p, line -> Log.info("[游戏] " + line));
            } catch (Exception e) {
                callbacks.log("启动失败: " + e.getMessage());
                callbacks.status("启动失败");
                Log.error("启动游戏失败", e);
            }
        });
    }

    public void stopGame() {
        if (gameProcess != null && gameProcess.isAlive()) {
            gameProcess.destroy();
            gameProcess = null;
            callbacks.log("游戏已终止");
        }
    }

    // ------------------------------------------------------------------
    // 模组 / 光影 / 数据包 / 资源包 / 世界 / 整合包
    // ------------------------------------------------------------------
    public List<ModManager.ModInfo> modsList(String version) {
        return modManager.getModsList(version);
    }

    public void installMod(String name) {
        runAsync(() -> modManager.installMod(name, config.getString("current_version")));
    }

    public void installShader(String name) {
        runAsync(() -> modManager.installShaderpack(name, config.getString("current_version")));
    }

    public void installDatapack(String name) {
        runAsync(() -> modManager.installDatapack(name, config.getString("current_version")));
    }

    public void installResourcepack(String name) {
        runAsync(() -> modManager.installResourcepack(name, config.getString("current_version")));
    }

    public void installWorld(String name) {
        runAsync(() -> modManager.installWorld(name, config.getString("current_version")));
    }

    public void installModpack(String name) {
        runAsync(() -> {
            try {
                String installedId = modManager.installModpackFromSearch(name);
                config.put("current_version", installedId);
                config.put("current_loader", VersionUtil.detectLoader(installedId));
                saveConfig();
                callbacks.log("整合包安装完成！启动版本: " + installedId);
                callbacks.status("整合包安装完成");
            } catch (Exception e) {
                callbacks.log("安装整合包失败: " + e.getMessage());
                callbacks.status("安装失败");
                Log.error("安装整合包失败", e);
            }
        });
    }

    public void toggleMod(String name, boolean disable) {
        runAsync(() -> {
            modManager.toggleMod(name, disable, config.getString("current_version"));
        });
    }

    public void importModpack(String path) {
        runAsync(() -> {
            try {
                String installedId = modManager.importModpack(path);
                String loader = VersionUtil.detectLoader(installedId);
                config.put("current_version", installedId);
                config.put("current_loader", loader);
                saveConfig();
                callbacks.log("整合包导入完成！启动版本: " + installedId);
                callbacks.status("整合包导入完成");
            } catch (Exception e) {
                callbacks.log("导入整合包失败: " + e.getMessage());
                callbacks.status("导入失败");
                Log.error("导入整合包失败", e);
            }
        });
    }

    // ------------------------------------------------------------------
    // 服务器
    // ------------------------------------------------------------------
    public List<String> installedServers() {
        return serverManager.installedServers();
    }

    public void installServer(String type, String version, String maxMem, String minMem) {
        runAsync(() -> serverManager.installServer(type, version, maxMem, minMem));
    }

    public void launchServer(String serverId) {
        runAsync(() -> {
            try {
                Process p = serverManager.launchServer(serverId,
                        config.getString("max_memory", "2G"), config.getString("min_memory", "1G"));
                readProcessLog(p, callbacks::serverLog);
            } catch (Exception e) {
                callbacks.log("启动服务器失败: " + e.getMessage());
            }
        });
    }

    public void stopServer() {
        serverManager.stopServer();
    }

    public void sendServerCommand(String command) {
        serverManager.sendCommand(command);
    }

    // ------------------------------------------------------------------
    // 其它
    // ------------------------------------------------------------------
    public void installJava(String majorVersion) {
        runAsync(() -> com.simplemc.launcher.misc.Extras.installJava(
                majorVersion, callbacks::log, callbacks::status, callbacks::progress));
    }

    public void checkUpdates() {
        runAsync(() -> {
            try {
                com.fasterxml.jackson.databind.JsonNode latest = com.simplemc.launcher.util.Downloader
                        .getJson("https://api.github.com/repos/BoscoWu/Simple-Minecraft-Launcher-SCL-/releases/latest");
                String tag = latest.has("tag_name") ? latest.get("tag_name").asText() : "";
                if (!tag.isEmpty()) {
                    callbacks.log("最新版本: " + tag);
                }
            } catch (Exception e) {
                callbacks.log("检查更新失败: " + e.getMessage());
            }
        });
    }

    // ------------------------------------------------------------------
    private void readProcessLog(Process p, Consumer<String> lineConsumer) {
        executor.submit(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        lineConsumer.accept(line.trim());
                    }
                }
            } catch (IOException ignored) {
            }
            try {
                p.waitFor();
            } catch (InterruptedException ignored) {
            }
            Log.info("[系统] 进程已退出，退出码: " + p.exitValue());
            callbacks.status("进程已退出");
        });
    }
}