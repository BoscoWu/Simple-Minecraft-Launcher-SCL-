package com.simplemc.launcher.ui.page;

import com.simplemc.launcher.core.JavaManager;
import com.simplemc.launcher.core.ServerManager;
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
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 服务器页：安装服务端、启动、控制台、EULA、删除。
 */
public final class ServerPage {

    private final MainWindow window;
    private final Config config;
    private final ServerManager serverManager;

    private final BorderPane root = new BorderPane();
    private final VBox serverListBox = new VBox(2);
    private final TextArea console = new TextArea();
    private final TextField commandInput = new TextField();

    private ComboBox<String> typeCombo;
    private TextField versionField;
    private TextField memField;

    public ServerPage(MainWindow window, Config config) {
        this.window = window;
        this.config = config;
        this.serverManager = new ServerManager(Constants.SERVER_DIR);
        serverManager.addLogListener(line ->
                Platform.runLater(() -> appendConsole(line)));
        serverManager.addExitListener(() ->
                Platform.runLater(() -> window.setStatus("服务器已停止")));
        build();
    }

    public BorderPane root() {
        return root;
    }

    private void build() {
        VBox page = new VBox(10);
        page.setPadding(new Insets(14));

        // ---------- 安装行 ----------
        HBox installRow = new HBox(8);
        installRow.setAlignment(Pos.CENTER_LEFT);

        installRow.getChildren().add(new Label("类型:"));
        typeCombo = new ComboBox<>();
        typeCombo.getItems().addAll("vanilla", "paper", "purpur", "fabric");
        typeCombo.setValue("paper");
        typeCombo.setPrefWidth(110);
        installRow.getChildren().add(typeCombo);

        installRow.getChildren().add(new Label("版本:"));
        versionField = new TextField();
        versionField.setPromptText("如 1.21.1");
        versionField.setPrefWidth(120);
        installRow.getChildren().add(versionField);

        installRow.getChildren().add(new Label("内存:"));
        memField = new TextField("2G");
        memField.setPrefWidth(70);
        installRow.getChildren().add(memField);

        Button installBtn = new Button("下载");
        installBtn.getStyleClass().add("btn-primary");
        installBtn.setOnAction(e -> doInstall());
        Button refreshBtn = new Button("刷新");
        refreshBtn.getStyleClass().add("btn-default");
        refreshBtn.setOnAction(e -> refreshServerList());
        installRow.getChildren().addAll(installBtn, refreshBtn);

        page.getChildren().add(installRow);

        // ---------- 服务器列表 ----------
        ScrollPane listScroll = new ScrollPane();
        listScroll.setFitToWidth(true);
        listScroll.setPrefHeight(120);
        listScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        serverListBox.setPadding(new Insets(6));
        serverListBox.setStyle("-fx-background-color: white; -fx-background-radius: 8;");
        listScroll.setContent(serverListBox);
        page.getChildren().add(listScroll);

        // ---------- 控制台标题行 ----------
        HBox consoleHead = new HBox(8);
        consoleHead.setAlignment(Pos.CENTER_LEFT);
        Label consoleTitle = new Label("服务器控制台");
        consoleTitle.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        Button clearBtn = new Button("清空日志");
        clearBtn.getStyleClass().add("btn-gray");
        clearBtn.setOnAction(e -> console.clear());
        Button copyBtn = new Button("复制");
        copyBtn.getStyleClass().add("btn-gray");
        copyBtn.setOnAction(e -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(console.getText());
            Clipboard.getSystemClipboard().setContent(cc);
        });
        consoleHead.getChildren().addAll(consoleTitle, sp, copyBtn, clearBtn);
        page.getChildren().add(consoleHead);

        // ---------- 控制台 ----------
        console.setEditable(false);
        console.setWrapText(false);
        console.setStyle("-fx-font-family: 'Consolas','Courier New',monospace;"
                + "-fx-font-size: 12px;");
        VBox.setVgrow(console, Priority.ALWAYS);
        page.getChildren().add(console);

        // ---------- 命令输入行 ----------
        HBox cmdRow = new HBox(8);
        cmdRow.setAlignment(Pos.CENTER_LEFT);
        commandInput.setPromptText("输入服务器命令，回车发送...");
        HBox.setHgrow(commandInput, Priority.ALWAYS);
        commandInput.setOnAction(e -> sendCommand());
        Button sendBtn = new Button("发送");
        sendBtn.getStyleClass().add("btn-primary");
        sendBtn.setOnAction(e -> sendCommand());
        Button stopBtn = new Button("停止");
        stopBtn.getStyleClass().add("btn-danger");
        stopBtn.setOnAction(e -> {
            window.setStatus("正在停止服务器...");
            CompletableFuture.runAsync(() -> {
                serverManager.stop();
                Platform.runLater(() -> window.setStatus("服务器已停止"));
            });
        });
        cmdRow.getChildren().addAll(commandInput, sendBtn, stopBtn);
        page.getChildren().add(cmdRow);

        root.setCenter(page);

        refreshServerList();
    }

    // ---------------- 列表 ----------------

    public void refreshServerList() {
        serverListBox.getChildren().clear();
        List<String> servers = serverManager.listServers();
        if (servers.isEmpty()) {
            Label empty = new Label("暂无服务器，请先下载");
            empty.getStyleClass().add("muted");
            empty.setPadding(new Insets(12));
            serverListBox.getChildren().add(empty);
            return;
        }
        for (String id : servers) {
            HBox row = new HBox(8);
            row.getStyleClass().add("server-row");
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(6, 8, 6, 8));

            Label name = new Label(id);
            name.setStyle("-fx-font-size: 13px;");
            HBox.setHgrow(name, Priority.ALWAYS);
            name.setMaxWidth(Double.MAX_VALUE);

            Button startBtn = new Button("启动");
            startBtn.getStyleClass().add("btn-primary");
            startBtn.setStyle("-fx-padding: 3 14 3 14; -fx-font-size: 12px;");
            startBtn.setOnAction(e -> startServer(id));

            Button deleteBtn = new Button("删除");
            deleteBtn.getStyleClass().add("btn-danger");
            deleteBtn.setStyle("-fx-padding: 3 14 3 14; -fx-font-size: 12px;");
            deleteBtn.setOnAction(e -> deleteServer(id));

            row.getChildren().addAll(name, startBtn, deleteBtn);
            serverListBox.getChildren().add(row);
        }
    }

    // ---------------- 安装 ----------------

    private void doInstall() {
        String type = typeCombo.getValue();
        String version = versionField.getText().trim();
        String mem = memField.getText().trim();
        if (version.isBlank()) {
            alert(Alert.AlertType.WARNING, "请输入服务器版本（如 1.21.1）");
            return;
        }
        if (mem.isBlank()) mem = "2G";

        final String memFinal = mem;
        CompletableFuture.runAsync(() -> {
            try {
                String id = serverManager.install(
                        ServerManager.ServerType.of(type), version, memFinal, "1G",
                        stage -> Platform.runLater(() -> {
                            window.setProgress(stage, 0.5, "");
                            appendConsole(stage);
                        }));
                Platform.runLater(() -> {
                    window.setProgress("服务器安装完成", 1.0, "100%");
                    refreshServerList();
                    alert(Alert.AlertType.INFORMATION, "服务端安装完成:\n" + id);
                });
            } catch (Exception e) {
                Log.error("服务器安装失败", e);
                Platform.runLater(() -> {
                    window.setProgress("安装失败", 0, "0%");
                    alert(Alert.AlertType.ERROR, "安装失败:\n" + e.getMessage());
                });
            }
        });
    }

    // ---------------- 启动 ----------------

    private void startServer(String serverId) {
        Path dir = serverManager.serverDir(serverId);

        // 检查 EULA
        if (!ServerManager.eulaAccepted(dir)) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("同意 Minecraft EULA");
            confirm.setHeaderText("首次启动服务器前，需要先同意 Minecraft 最终用户许可协议");
            confirm.setContentText(
                    "https://aka.ms/MinecraftEULA\n\n"
                    + "服务器：" + serverId + "\n\n"
                    + "点击「是」表示你接受该协议，程序会写入 eula=true 并启动服务器。");
            confirm.initOwner(window.stage());
            Optional<ButtonType> r = confirm.showAndWait();
            if (r.isEmpty() || r.get() != ButtonType.OK && r.get() != ButtonType.YES) {
                appendConsole("[系统] 已取消启动（未同意 EULA）");
                return;
            }
            try {
                ServerManager.setEula(dir, true);
                appendConsole("[系统] 已同意 EULA，写入 eula=true");
            } catch (Exception e) {
                alert(Alert.AlertType.ERROR, "写入 eula.txt 失败:\n" + e.getMessage());
                return;
            }
        }

        // 选 Java
        List<JavaManager.JavaInfo> javas = JavaManager.detectAll();
        JavaManager.JavaInfo java = JavaManager.pickBest(javas, 17);
        String javaBin = java != null ? java.path() : "java";

        String maxMem = config.getString("server_max_mem");
        String minMem = config.getString("server_min_mem");

        CompletableFuture.runAsync(() -> {
            try {
                serverManager.start(serverId, javaBin, maxMem, minMem);
                Platform.runLater(() -> {
                    window.setStatus("服务器运行中: " + serverId);
                    alert(Alert.AlertType.INFORMATION, "服务器已启动: " + serverId);
                });
            } catch (Exception e) {
                Log.error("启动服务器失败", e);
                Platform.runLater(() -> {
                    window.setStatus("服务器启动失败");
                    alert(Alert.AlertType.ERROR, "启动失败:\n" + e.getMessage());
                });
            }
        });
    }

    // ---------------- 删除 ----------------

    private void deleteServer(String serverId) {
        long size = serverManager.serverSize(serverId);
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("删除服务器");
        confirm.setHeaderText("确定要删除服务器吗？");
        confirm.setContentText(
                "服务器：" + serverId + "\n"
                + "占用空间：" + Downloader.humanSize(size) + "\n\n"
                + "这会永久删除该服务器的所有文件，包括世界存档、插件与配置，操作不可恢复。"
                + (serverManager.isRunning() ? "\n\n注意：删除前会先停止服务器。" : ""));
        confirm.initOwner(window.stage());
        Optional<ButtonType> r = confirm.showAndWait();
        if (r.isEmpty() || (r.get() != ButtonType.OK && r.get() != ButtonType.YES)) {
            return;
        }

        CompletableFuture.runAsync(() -> {
            boolean ok = serverManager.delete(serverId);
            Platform.runLater(() -> {
                refreshServerList();
                if (ok) {
                    appendConsole("[系统] 服务器 " + serverId + " 已删除");
                    window.setStatus("已删除 " + serverId);
                } else {
                    alert(Alert.AlertType.ERROR,
                            "无法删除服务器 " + serverId + "\n\n"
                            + "常见原因：服务器仍在运行或文件被占用。\n"
                            + "请先点击「停止」后重试。");
                }
            });
        });
    }

    // ---------------- 控制台 ----------------

    private void sendCommand() {
        String cmd = commandInput.getText().trim();
        if (cmd.isEmpty()) return;
        if (!serverManager.isRunning()) {
            appendConsole("[系统] 服务器未运行");
            return;
        }
        serverManager.sendCommand(cmd);
        commandInput.clear();
    }

    private void appendConsole(String line) {
        console.appendText(line + "\n");
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
