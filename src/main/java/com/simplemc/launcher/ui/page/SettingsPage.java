package com.simplemc.launcher.ui.page;

import com.simplemc.launcher.core.JavaManager;
import com.simplemc.launcher.core.UpdateManager;
import com.simplemc.launcher.ui.MainWindow;
import com.simplemc.launcher.ui.Theme;
import com.simplemc.launcher.util.Config;
import com.simplemc.launcher.util.Constants;
import com.simplemc.launcher.util.Downloader;
import com.simplemc.launcher.util.Log;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 设置页：启动选项、内存、Java、更新。
 */
public final class SettingsPage {

    private final MainWindow window;
    private final Config config;
    private final BorderPane root = new BorderPane();

    private TextField windowTitleField;
    private TextField jvmArgsField;
    private TextField gameArgsField;
    private TextField customInfoField;
    private TextField proxyField;
    private ComboBox<String> isolationCombo;
    private ComboBox<String> visibleCombo;
    private ComboBox<String> priorityCombo;
    private ComboBox<String> windowSizeCombo;
    private ComboBox<String> authModeCombo;
    private ComboBox<String> ipProtocolCombo;
    private ComboBox<String> downloadSourceCombo;
    private Spinner<Integer> threadsSpinner;
    private CheckBox autoMemCheck;
    private Slider maxMemSlider;
    private Slider minMemSlider;
    private Label maxMemLabel;
    private Label minMemLabel;
    private ComboBox<String> javaCombo;
    private Label updateStatus;
    private TextArea logArea;

    public SettingsPage(MainWindow window, Config config) {
        this.window = window;
        this.config = config;
        build();
    }

    public BorderPane root() {
        return root;
    }

    private void build() {
        VBox page = new VBox(14);
        page.setPadding(new Insets(18));

        ScrollPane scroll = new ScrollPane(page);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        root.setCenter(scroll);
        // 每次显示时回到顶部（否则可能停在中间位置）
        root.sceneProperty().addListener((o, oldScene, newScene) -> {
            if (newScene != null) {
                Platform.runLater(() -> scroll.setVvalue(0));
            }
        });
        root.visibleProperty().addListener((o, was, now) -> {
            if (now) Platform.runLater(() -> scroll.setVvalue(0));
        });

        // ---------- 启动选项（完整） ----------
        VBox launchBox = new VBox(10);

        // 默认实例隔离
        isolationCombo = new ComboBox<>();
        isolationCombo.setMaxWidth(Double.MAX_VALUE);
        isolationCombo.getItems().addAll("隔离所有实例", "不隔离", "仅隔离整合包");
        isolationCombo.setValue(config.getString("instance_isolation_mode"));
        if (isolationCombo.getValue() == null || isolationCombo.getValue().isBlank()) {
            isolationCombo.setValue("隔离所有实例");
        }
        launchBox.getChildren().add(labeledField("默认实例隔离", isolationCombo));

        // 游戏窗口标题
        windowTitleField = new TextField(config.getString("window_title"));
        launchBox.getChildren().add(labeledField("游戏窗口标题", windowTitleField));

        // 自定义信息
        customInfoField = new TextField(config.getString("custom_info"));
        launchBox.getChildren().add(labeledField("自定义信息", customInfoField));

        // 启动器可见性
        visibleCombo = new ComboBox<>();
        visibleCombo.setMaxWidth(Double.MAX_VALUE);
        visibleCombo.getItems().addAll("游戏启动后仍保持不变", "游戏启动后最小化", "游戏启动后隐藏");
        visibleCombo.setValue(config.getString("launcher_visibility"));
        if (visibleCombo.getValue() == null || visibleCombo.getValue().isBlank()) {
            visibleCombo.setValue("游戏启动后仍保持不变");
        }
        launchBox.getChildren().add(labeledField("启动器可见性", visibleCombo));

        // 进程优先级
        priorityCombo = new ComboBox<>();
        priorityCombo.setMaxWidth(Double.MAX_VALUE);
        priorityCombo.getItems().addAll(
                "高（优先保证游戏运行，游戏性能更佳，但可能造成其他程序卡顿）",
                "普通", "低（不影响其他程序，但游戏性能可能下降）", "实时（不推荐）");
        priorityCombo.setValue(config.getString("process_priority"));
        if (priorityCombo.getValue() == null || priorityCombo.getValue().isBlank()) {
            priorityCombo.setValue("普通");
        }
        launchBox.getChildren().add(labeledField("进程优先级", priorityCombo));

        // 窗口大小
        windowSizeCombo = new ComboBox<>();
        windowSizeCombo.setMaxWidth(Double.MAX_VALUE);
        windowSizeCombo.getItems().addAll(
                "默认", "854×480", "1280×720", "1366×768", "1600×900", "1920×1080", "全屏");
        windowSizeCombo.setValue(config.getString("window_size"));
        if (windowSizeCombo.getValue() == null || windowSizeCombo.getValue().isBlank()) {
            windowSizeCombo.setValue("默认");
        }
        launchBox.getChildren().add(labeledField("窗口大小", windowSizeCombo));

        // 正版验证方式
        authModeCombo = new ComboBox<>();
        authModeCombo.setMaxWidth(Double.MAX_VALUE);
        authModeCombo.getItems().addAll("设备代码流", "浏览器跳转");
        authModeCombo.setValue(config.getString("auth_flow"));
        if (authModeCombo.getValue() == null || authModeCombo.getValue().isBlank()) {
            authModeCombo.setValue("设备代码流");
        }
        launchBox.getChildren().add(labeledField("正版验证方式", authModeCombo));

        // IP 协议偏好
        ipProtocolCombo = new ComboBox<>();
        ipProtocolCombo.setMaxWidth(Double.MAX_VALUE);
        ipProtocolCombo.getItems().addAll("Java 默认", "优先 IPv4", "优先 IPv6");
        ipProtocolCombo.setValue(config.getString("ip_preference"));
        if (ipProtocolCombo.getValue() == null || ipProtocolCombo.getValue().isBlank()) {
            ipProtocolCombo.setValue("Java 默认");
        }
        launchBox.getChildren().add(labeledField("IP 协议偏好", ipProtocolCombo));

        // 代理设置
        proxyField = new TextField(config.getString("proxy"));
        proxyField.setPromptText("如 http://127.0.0.1:7890（留空使用 Java 默认）");
        launchBox.getChildren().add(labeledField("代理设置", proxyField));

        page.getChildren().add(sectionCard("启动选项", launchBox));

        // ---------- 游戏内存 ----------
        maxMemSlider = new Slider(512, 16384, parseInt(config.getString("max_mem"), 2048));
        maxMemSlider.setBlockIncrement(512);
        maxMemSlider.setMajorTickUnit(2048);
        maxMemSlider.setSnapToTicks(false);
        maxMemSlider.setShowTickLabels(false);
        maxMemLabel = new Label();
        maxMemLabel.setStyle("-fx-text-fill: " + Theme.PRIMARY + "; -fx-font-weight: bold;");
        maxMemSlider.valueProperty().addListener((o, a, b) -> {
            maxMemLabel.setText(String.format("%.0f MB", b.doubleValue()));
            if (minMemSlider != null && b.doubleValue() < minMemSlider.getValue()) {
                minMemSlider.setValue(b.doubleValue());
            }
        });
        maxMemLabel.setText(String.format("%.0f MB", maxMemSlider.getValue()));

        minMemSlider = new Slider(256, Math.max(4096, maxMemSlider.getValue()),
                parseInt(config.getString("min_mem"), 1024));
        minMemSlider.setBlockIncrement(256);
        minMemLabel = new Label();
        minMemLabel.setStyle("-fx-text-fill: " + Theme.PRIMARY + "; -fx-font-weight: bold;");
        minMemSlider.valueProperty().addListener((o, a, b) -> {
            minMemLabel.setText(String.format("%.0f MB", b.doubleValue()));
            minMemSlider.setMax(maxMemSlider.getValue());
            if (b.doubleValue() > maxMemSlider.getValue()) {
                maxMemSlider.setValue(b.doubleValue());
            }
        });
        minMemLabel.setText(String.format("%.0f MB", minMemSlider.getValue()));

        VBox memBox = new VBox(8);
        autoMemCheck = new CheckBox("自动配置内存（推荐）");
        autoMemCheck.setSelected(config.getBoolean("auto_memory"));
        autoMemCheck.selectedProperty().addListener((o, a, b) -> {
            maxMemSlider.setDisable(b);
            minMemSlider.setDisable(b);
        });
        maxMemSlider.setDisable(autoMemCheck.isSelected());
        minMemSlider.setDisable(autoMemCheck.isSelected());

        HBox maxRow = new HBox(10, new Label("最大内存 (Xmx):"), maxMemLabel);
        maxRow.setAlignment(Pos.CENTER_LEFT);
        HBox minRow = new HBox(10, new Label("初始内存 (Xms):"), minMemLabel);
        minRow.setAlignment(Pos.CENTER_LEFT);
        memBox.getChildren().addAll(autoMemCheck, maxRow, maxMemSlider,
                minRow, minMemSlider);
        page.getChildren().add(sectionCard("游戏内存", memBox));

        // ---------- 高级启动选项 ----------
        VBox advBox = new VBox(10);
        jvmArgsField = new TextField(config.getString("jvm_args"));
        jvmArgsField.setPromptText("如 -XX:+UseG1GC -XX:MaxGCPauseMillis=50");
        advBox.getChildren().add(labeledField("JVM 参数", jvmArgsField));

        gameArgsField = new TextField(config.getString("game_args"));
        gameArgsField.setPromptText("额外传给 Minecraft 的参数");
        advBox.getChildren().add(labeledField("游戏参数", gameArgsField));

        page.getChildren().add(sectionCard("高级启动选项", advBox));

        // ---------- 下载设置 ----------
        VBox dlBox = new VBox(10);

        HBox threadRow = new HBox(10);
        threadRow.setAlignment(Pos.CENTER_LEFT);
        Label threadLabel = new Label("下载线程数");
        threadLabel.setPrefWidth(110);
        threadLabel.setMinWidth(110);
        int savedThreads = Integer.parseInt(
                config.getString("download_threads").isBlank() ? "8"
                        : config.getString("download_threads").replaceAll("\\D", ""));
        if (savedThreads < 1) savedThreads = 8;
        threadsSpinner = new Spinner<>(1, 32, Math.min(32, savedThreads));
        threadsSpinner.setEditable(true);
        threadsSpinner.setPrefWidth(100);
        Label threadHint = new Label("并发连接数，越高越快但网络差时容易失败（推荐 8-16）");
        threadHint.setStyle("-fx-font-size: 11.5px; -fx-text-fill: " + Theme.TEXT_MUTED + ";");
        threadRow.getChildren().addAll(threadLabel, threadsSpinner, threadHint);
        dlBox.getChildren().add(threadRow);

        // 下载源偏好
        HBox sourceRow = new HBox(10);
        sourceRow.setAlignment(Pos.CENTER_LEFT);
        Label sourceLabel = new Label("游戏资源来源");
        sourceLabel.setPrefWidth(110);
        sourceLabel.setMinWidth(110);
        downloadSourceCombo = new ComboBox<>();
        downloadSourceCombo.setMaxWidth(Double.MAX_VALUE);
        downloadSourceCombo.getItems().addAll(
                "官方 CDN（推荐，速度最快）", "BMCLAPI 国内镜像", "自动（先官方后镜像）");
        String savedSource = config.getString("download_source");
        downloadSourceCombo.setValue(
                savedSource == null || savedSource.isBlank()
                        ? "官方 CDN（推荐，速度最快）" : savedSource);
        downloadSourceCombo.setPrefWidth(260);
        sourceRow.getChildren().addAll(sourceLabel, downloadSourceCombo);
        dlBox.getChildren().add(sourceRow);

        page.getChildren().add(sectionCard("下载设置", dlBox));

        // ---------- Java ----------
        VBox javaBox = new VBox(10);
        HBox javaRow = new HBox(8);
        javaRow.setAlignment(Pos.CENTER_LEFT);
        javaCombo = new ComboBox<>();
        javaCombo.setPrefWidth(420);
        HBox.setHgrow(javaCombo, Priority.ALWAYS);
        Button detectBtn = new Button("重新检测");
        detectBtn.getStyleClass().add("btn-default");
        detectBtn.setOnAction(e -> detectJava());
        javaRow.getChildren().addAll(new Label("Java 路径:"), javaCombo, detectBtn);

        HBox javaInstallRow = new HBox(8);
        javaInstallRow.setAlignment(Pos.CENTER_LEFT);
        ComboBox<Integer> majorCombo = new ComboBox<>();
        majorCombo.getItems().addAll(8, 11, 17, 21);
        majorCombo.setValue(21);
        Button installJavaBtn = new Button("下载安装 Java");
        installJavaBtn.getStyleClass().add("btn-primary");
        installJavaBtn.setOnAction(e -> installJava(majorCombo.getValue()));
        javaInstallRow.getChildren().addAll(
                new Label("安装版本:"), majorCombo, installJavaBtn);

        javaBox.getChildren().addAll(javaRow, javaInstallRow);
        page.getChildren().add(sectionCard("Java 运行时", javaBox));

        // ---------- 启动器更新 ----------
        VBox updateBox = new VBox(10);
        HBox updateRow = new HBox(10);
        updateRow.setAlignment(Pos.CENTER_LEFT);
        updateStatus = new Label("当前版本: " + Constants.VERSION);
        updateStatus.getStyleClass().add("muted");
        Button checkBtn = new Button("检查更新");
        checkBtn.getStyleClass().add("btn-primary");
        checkBtn.setOnAction(e -> checkUpdate());
        updateRow.getChildren().addAll(updateStatus, checkBtn);

        CheckBox autoUpdate = new CheckBox("启动时自动检查更新");
        autoUpdate.setSelected(config.getBoolean("auto_check_update"));
        autoUpdate.setStyle("-fx-text-fill: " + Theme.TEXT + ";");
        autoUpdate.selectedProperty().addListener((o, a, b) ->
                config.set("auto_check_update", b).save());

        updateBox.getChildren().addAll(updateRow, autoUpdate);
        page.getChildren().add(sectionCard("启动器更新", updateBox));

        // ---------- 日志 ----------
        VBox logBox = new VBox(8);
        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setPrefHeight(160);
        logArea.setStyle("-fx-font-family: 'Consolas',monospace; -fx-font-size: 11px;");
        Log.setUiSink(line -> Platform.runLater(() -> {
            if (logArea != null) {
                logArea.appendText(line + "\n");
            }
        }));
        HBox logBtns = new HBox(8);
        Button clearLog = new Button("清空");
        clearLog.getStyleClass().add("btn-gray");
        clearLog.setOnAction(e -> logArea.clear());
        Button openLog = new Button("打开日志文件");
        openLog.getStyleClass().add("btn-gray");
        openLog.setOnAction(e -> openPath(Constants.LOG_FILE.toString()));
        logBtns.getChildren().addAll(clearLog, openLog);
        logBox.getChildren().addAll(logArea, logBtns);
        page.getChildren().add(sectionCard("运行日志", logBox));

        // ---------- 底部按钮 ----------
        HBox bottomRow = new HBox(10);
        bottomRow.setAlignment(Pos.CENTER_RIGHT);
        bottomRow.setPadding(new Insets(4, 0, 20, 0));
        Button resetBtn = new Button("恢复默认");
        resetBtn.getStyleClass().add("btn-gray");
        resetBtn.setOnAction(e -> resetDefaults());
        Button saveBtn = new Button("保存设置");
        saveBtn.getStyleClass().add("btn-primary");
        saveBtn.setOnAction(e -> saveSettings());
        bottomRow.getChildren().addAll(resetBtn, saveBtn);
        page.getChildren().add(bottomRow);

        // 首次加载 Java
        Platform.runLater(this::detectJava);
    }

    private VBox sectionCard(String title, Region content) {
        VBox card = new VBox(10);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(14));
        Label t = new Label(title);
        t.getStyleClass().add("section-title");
        card.getChildren().addAll(t, content);
        return card;
    }

    private HBox labeledField(String label, Region field) {
        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);

        Label l = new Label(label);
        l.setPrefWidth(120);
        l.setMinWidth(120);
        l.setMaxWidth(120);
        l.setWrapText(false);
        l.setStyle("-fx-font-size: 12.5px; -fx-text-fill: " + Theme.TEXT + ";");

        // 控件占满剩余宽度，但不要超出
        field.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(field, Priority.ALWAYS);

        row.getChildren().addAll(l, field);
        return row;
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    // ---------------- 操作 ----------------

    private void detectJava() {
        javaCombo.getItems().clear();
        CompletableFuture.runAsync(() -> {
            List<JavaManager.JavaInfo> list = JavaManager.detectAll();
            Platform.runLater(() -> {
                if (list.isEmpty()) {
                    javaCombo.getItems().add("未检测到 Java，请先安装");
                } else {
                    for (JavaManager.JavaInfo j : list) {
                        javaCombo.getItems().add(j.toString());
                    }
                    javaCombo.getSelectionModel().selectFirst();
                }
            });
        });
    }

    private void installJava(Integer major) {
        if (major == null) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "将下载安装 Java " + major + "（约 180MB），是否继续？",
                ButtonType.YES, ButtonType.NO);
        confirm.initOwner(window.stage());
        confirm.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.YES) return;
            CompletableFuture.runAsync(() -> {
                try {
                    JavaManager.install(major, s -> Platform.runLater(() ->
                            window.setProgress(s, 0.5, "")));
                    Platform.runLater(() -> {
                        window.setProgress("Java 安装完成", 1.0, "100%");
                        alert(Alert.AlertType.INFORMATION, "Java " + major + " 安装完成");
                        detectJava();
                    });
                } catch (Exception e) {
                    Log.error("Java 安装失败", e);
                    Platform.runLater(() -> {
                        window.setProgress("安装失败", 0, "0%");
                        alert(Alert.AlertType.ERROR, "Java 安装失败:\n" + e.getMessage());
                    });
                }
            });
        });
    }

    private void checkUpdate() {
        updateStatus.setText("正在检查...");
        CompletableFuture.runAsync(() -> {
            UpdateManager.CheckResult r = UpdateManager.check();
            Platform.runLater(() -> {
                if (r.hasUpdate()) {
                    updateStatus.setText(r.message());
                    updateStatus.setStyle("-fx-text-fill: " + Theme.SUCCESS + ";");
                    Alert a = new Alert(Alert.AlertType.CONFIRMATION);
                    a.setTitle("发现新版本");
                    a.setHeaderText(r.message());
                    a.setContentText("是否立即下载并更新？");
                    a.initOwner(window.stage());
                    a.showAndWait().ifPresent(bt -> {
                        if (bt == ButtonType.OK || bt == ButtonType.YES) {
                            downloadUpdate(r);
                        }
                    });
                } else {
                    updateStatus.setText(r.message());
                    updateStatus.setStyle("-fx-text-fill: " + Theme.TEXT_MUTED + ";");
                    alert(Alert.AlertType.INFORMATION, r.message());
                }
            });
        });
    }

    private void downloadUpdate(UpdateManager.CheckResult r) {
        CompletableFuture.runAsync(() -> {
            try {
                java.nio.file.Path file = UpdateManager.download(r,
                        s -> Platform.runLater(() -> window.setProgress(s, 0.3, "")),
                        (cur, total) -> {
                            double f = total > 0 ? 0.3 + 0.7 * cur / total : 0.5;
                            Platform.runLater(() -> window.setProgress(
                                    "下载更新", f, String.format("%.0f%%", f * 100)));
                        });

                Platform.runLater(() -> {
                    Alert a = new Alert(Alert.AlertType.CONFIRMATION);
                    a.setTitle("更新已下载");
                    a.setHeaderText("更新包已下载完成");
                    a.setContentText("是否立即重启并应用更新？");
                    a.initOwner(window.stage());
                    a.showAndWait().ifPresent(bt -> {
                        if (bt == ButtonType.OK || bt == ButtonType.YES) {
                            try {
                                UpdateManager.applyAndRestart(file);
                                Platform.exit();
                                System.exit(0);
                            } catch (Exception e) {
                                alert(Alert.AlertType.ERROR,
                                        "应用更新失败:\n" + e.getMessage()
                                        + "\n\n新版本文件位置:\n" + file);
                            }
                        }
                    });
                });
            } catch (Exception e) {
                Log.error("下载更新失败", e);
                Platform.runLater(() -> alert(Alert.AlertType.ERROR,
                        "下载更新失败:\n" + e.getMessage()));
            }
        });
    }

    private void saveSettings() {
        config.set("window_title", windowTitleField.getText())
                .set("jvm_args", jvmArgsField.getText())
                .set("game_args", gameArgsField.getText())
                .set("auto_memory", autoMemCheck.isSelected())
                .set("max_mem", String.valueOf((int) maxMemSlider.getValue()))
                .set("min_mem", String.valueOf((int) minMemSlider.getValue()))
                .set("instance_isolation_mode", isolationCombo.getValue())
                .set("custom_info", customInfoField.getText())
                .set("launcher_visibility", visibleCombo.getValue())
                .set("process_priority", priorityCombo.getValue())
                .set("window_size", windowSizeCombo.getValue())
                .set("auth_flow", authModeCombo.getValue())
                .set("ip_preference", ipProtocolCombo.getValue())
                .set("proxy", proxyField.getText())
                .set("download_threads", String.valueOf(threadsSpinner.getValue()))
                .set("download_source", downloadSourceCombo.getValue())
                .save();
        // 立即生效：更新下载器的并发数
        com.simplemc.launcher.util.Downloader.setDefaultConnections(
                threadsSpinner.getValue());
        alert(Alert.AlertType.INFORMATION,
                "设置已保存（下载线程数：" + threadsSpinner.getValue() + "）");
        Log.info("设置已保存, 下载线程=" + threadsSpinner.getValue()
                + ", 下载源=" + downloadSourceCombo.getValue());
    }

    private void resetDefaults() {
        windowTitleField.setText("Minecraft");
        jvmArgsField.setText("");
        gameArgsField.setText("");
        customInfoField.setText("SimpleMCLauncher");
        proxyField.setText("");
        isolationCombo.setValue("隔离所有实例");
        visibleCombo.setValue("游戏启动后仍保持不变");
        priorityCombo.setValue("普通");
        windowSizeCombo.setValue("默认");
        authModeCombo.setValue("设备代码流");
        ipProtocolCombo.setValue("Java 默认");
        threadsSpinner.getValueFactory().setValue(8);
        downloadSourceCombo.setValue("官方 CDN（推荐，速度最快）");
        autoMemCheck.setSelected(true);
        maxMemSlider.setValue(2048);
        minMemSlider.setValue(1024);
        alert(Alert.AlertType.INFORMATION, "已恢复默认值，记得点击「保存设置」");
    }

    private void openPath(String path) {
        try {
            java.awt.Desktop.getDesktop().open(new java.io.File(path).getParentFile());
        } catch (Exception e) {
            Log.warn("打开路径失败: " + e.getMessage());
        }
    }

    private void alert(Alert.AlertType type, String msg) {
        Platform.runLater(() -> {
            Alert a = new Alert(type);
            a.setTitle(type == Alert.AlertType.ERROR ? "错误" : "提示");
            a.setHeaderText(null);
            a.setContentText(msg);
            a.initOwner(window.stage());
            a.showAndWait();
        });
    }
}
