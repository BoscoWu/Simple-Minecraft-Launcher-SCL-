package com.simplemc.launcher;

import com.simplemc.launcher.ui.MainWindow;
import com.simplemc.launcher.ui.SplashScreen;
import com.simplemc.launcher.ui.page.DownloadPage;
import com.simplemc.launcher.ui.page.HomePage;
import com.simplemc.launcher.ui.page.ServerPage;
import com.simplemc.launcher.ui.page.SettingsPage;
import com.simplemc.launcher.util.Config;
import com.simplemc.launcher.util.Constants;
import com.simplemc.launcher.util.Log;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;

/**
 * 程序入口。
 */
public class LauncherApp extends Application {

    private Config config;
    private MainWindow window;
    private SplashScreen splash;

    public static void main(String[] args) {
        // 单实例检查
        Log.info("========== 极简 MC 启动器 " + Constants.VERSION + " 启动 ==========");
        Log.info("数据目录: " + Constants.DATA_DIR);

        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        Constants.ensureDirectories();
        config = new Config();

        // 应用已保存的下载线程数
        String savedThreads = config.getString("download_threads");
        int threads = 8;
        if (savedThreads != null && !savedThreads.isBlank()) {
            try {
                threads = Integer.parseInt(savedThreads.replaceAll("\\D", ""));
            } catch (Exception ignored) {
            }
        }
        com.simplemc.launcher.util.Downloader.setDefaultConnections(threads);
        Log.info("启动日志: 下载线程数=" + threads
                + (Constants.useRoundedCorners() ? ", 圆角界面" : ", 直角界面"));

        // 先显示闪屏
        splash = new SplashScreen();
        splash.show();
        splash.setStatus("正在初始化...");

        // 主窗口在后台准备好后再显示，确保闪屏先出现
        new Thread(() -> {
            try {
                Thread.sleep(150);
                Platform.runLater(() -> {
                    splash.setStatus("加载界面...");
                    buildMain(primaryStage);
                });
            } catch (Exception e) {
                Log.error("启动失败", e);
            }
        }, "startup").start();
    }

    private void buildMain(Stage stage) {
        try {
            window = new MainWindow(stage);
            config = new Config();

            splash.setStatus("加载启动页...");
            HomePage home = new HomePage(window, config);
            window.addPage("启动", () -> {
                window.contentHost().getChildren().setAll(home.root());
            });

            splash.setStatus("加载下载页...");
            DownloadPage download = new DownloadPage(window, config);
            window.addPage("下载", () -> {
                window.contentHost().getChildren().setAll(download.root());
            });

            // 全窗口拖拽导入整合包：拖到任意页面都能导入
            window.setModpackDropHandler(download::importModpackFromPath);

            splash.setStatus("加载服务器页...");
            ServerPage server = new ServerPage(window, config);
            window.addPage("服务器", () -> {
                window.contentHost().getChildren().setAll(server.root());
                server.refreshServerList();
            });

            splash.setStatus("加载设置页...");
            SettingsPage settings = new SettingsPage(window, config);
            window.addPage("设置", () -> {
                window.contentHost().getChildren().setAll(settings.root());
            });

            splash.setStatus("准备就绪");

            // 显示主窗口，然后关闭闪屏
            window.show();
            window.switchTo("启动");

            Platform.runLater(() -> {
                try {
                    Thread.sleep(120);
                } catch (Exception ignored) {}
                splash.close();
                Log.info("界面加载完成");
            });

            // 后台自动检查更新
            if (config.getBoolean("auto_check_update")) {
                new Thread(() -> {
                    try {
                        Thread.sleep(2500);
                        var r = com.simplemc.launcher.core.UpdateManager.check();
                        if (r.hasUpdate()) {
                            Platform.runLater(() -> {
                                javafx.scene.control.Alert a =
                                        new javafx.scene.control.Alert(
                                                javafx.scene.control.Alert.AlertType.INFORMATION);
                                a.setTitle("发现新版本");
                                a.setHeaderText(r.message());
                                a.setContentText("可在「设置 → 启动器更新」中下载安装。");
                                a.initOwner(stage);
                                a.show();
                            });
                        } else {
                            Log.info("自动检查：" + r.message());
                        }
                    } catch (Exception e) {
                        Log.warn("自动检查更新失败: " + e.getMessage());
                    }
                }, "auto-update").start();
            }
        } catch (Exception e) {
            Log.error("构建主界面失败", e);
            if (splash != null) splash.close();
            javafx.scene.control.Alert a = new javafx.scene.control.Alert(
                    javafx.scene.control.Alert.AlertType.ERROR);
            a.setTitle("启动失败");
            a.setHeaderText("无法启动启动器");
            a.setContentText(String.valueOf(e.getMessage()));
            a.showAndWait();
            Platform.exit();
        }
    }

    @Override
    public void stop() {
        Log.info("启动器退出");
    }
}
