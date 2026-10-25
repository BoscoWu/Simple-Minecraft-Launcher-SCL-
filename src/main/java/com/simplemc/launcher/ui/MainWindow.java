package com.simplemc.launcher.ui;

import com.simplemc.launcher.LauncherCore;
import com.simplemc.launcher.auth.MicrosoftAuth;
import com.simplemc.launcher.util.Api;
import com.simplemc.launcher.util.AppPaths;
import com.fasterxml.jackson.databind.JsonNode;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainWindow {

    private final Stage stage;
    private final LauncherCore core;

    private HBox topBar;
    private HBox navBox;

    private final ExecutorService uiExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        return t;
    });

    private final Label statusLabel = new Label("就绪");
    private final ComboBox<String> versionCombo = new ComboBox<>();

    // 左侧边栏组件
    private VBox sidebar;
    private ImageView avatarView;
    private final Label sidebarPlayerName = new Label("Player");
    private final Label sidebarLoginStatus = new Label("离线验证");
    private final TextField offlineNameField = new TextField("Player");
    private final Button launchGameBtn;
    private final Label currentVersionLabel = new Label("未选择版本");

    // 服务器页组件
    private final TextArea serverLogArea = new TextArea();
    private final TextField serverCommandField = new TextField();
    private final ComboBox<String> serverTypeMenu = new ComboBox<>();
    private final TextField serverVersionEntry = new TextField();
    private final TextField serverMemEntry = new TextField("2G");
    private final ListView<String> serversList = new ListView<>();

    // ===== 下载页分栏组件 =====
    private ListView<String> categoryListView;
    private StackPane rightDownloadStack;
    private final Map<String, Node> downloadViews = new LinkedHashMap<>();
    private String selectedMcVersion = "";

    // 右侧的 Minecraft 下载组件
    private StackPane mcRightPane;
    private VBox mcVersionListView;
    private VBox mcLoaderSelectView;
    private Label downloadVersionLabel;
    private ProgressBar downloadProgressBar;
    private TextArea downloadLogArea;
    private Label downloadStatusLabel;
    private Accordion mcVersionAccordion;
    private boolean mcVersionsLoaded = false;
    private Button downloadBtn;
    private Button cancelDownloadBtn;

    // ===== 设置页组件 =====
    private Slider maxMemSlider;
    private Label maxMemLabel;
    private Slider minMemSlider;
    private Label minMemLabel;
    private ComboBox<String> downloadSourceCombo;

    // 页面缓存
    private VBox homePage, serversPage, downloadPage, settingsPage;
    private StackPane contentStack;

    public MainWindow(Stage stage) {
        this.stage = stage;

        this.core = new LauncherCore(new LauncherCore.Callbacks() {
            @Override
            public void log(String message) {
                Platform.runLater(() -> {
                    if (downloadLogArea != null) downloadLogArea.appendText(message + "\n");
                });
            }
            @Override
            public void status(String message) {
                Platform.runLater(() -> {
                    statusLabel.setText(message);
                    if (downloadStatusLabel != null) downloadStatusLabel.setText(message);
                });
            }
            @Override
            public void progress(long current, long total) {
                Platform.runLater(() -> {
                    if (total > 0 && downloadProgressBar != null) {
                        downloadProgressBar.setProgress(Math.min(1.0, (double) current / (double) total));
                    }
                });
            }
            @Override
            public void serverLog(String message) {
                Platform.runLater(() -> serverLogArea.appendText(message + "\n"));
            }
        });

        Label launchText = new Label("启动游戏");
        launchText.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #0057a5;");
        currentVersionLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #666;");
        VBox launchGameBtnContent = new VBox(4, launchText, currentVersionLabel);
        launchGameBtnContent.setAlignment(Pos.CENTER);

        launchGameBtn = new Button();
        launchGameBtn.setGraphic(launchGameBtnContent);
        launchGameBtn.setMaxWidth(Double.MAX_VALUE);
        launchGameBtn.setPrefHeight(60);
        launchGameBtn.setStyle("-fx-background-color: #ebf4ff; -fx-border-color: #0057a5; -fx-border-radius: 5px; -fx-background-radius: 5px; -fx-cursor: hand;");
        launchGameBtn.setOnAction(e -> {
            String v = versionCombo.getValue();
            if (v == null || v.isBlank()) {
                if (downloadLogArea != null) downloadLogArea.appendText("请先选择要启动的版本\n");
            } else {
                core.launch(v);
            }
        });

        buildUI();
        refreshVersions();
        refreshServers();
        updatePlayerDisplay();

        stage.setOnCloseRequest(e -> {
            core.shutdown();
            uiExecutor.shutdownNow();
            Platform.exit();
        });
    }

    private void buildUI() {
        // ================= 顶部导航栏 =================
        topBar = new HBox(10);
        topBar.setAlignment(Pos.CENTER);
        topBar.setPadding(new Insets(10, 20, 10, 20));
        topBar.setStyle("-fx-background-color: #3572b0;");

        Label logo = new Label("Simple MC");
        logo.setStyle("-fx-text-fill: white; -fx-font-size: 20px; -fx-font-weight: bold;");

        Region leftSpacer = new Region();
        HBox.setHgrow(leftSpacer, Priority.ALWAYS);

        navBox = new HBox(20);
        navBox.setAlignment(Pos.CENTER);

        String normalStyle = "-fx-background-color: transparent; -fx-text-fill: rgba(255,255,255,0.8); -fx-font-size: 15px; -fx-cursor: hand; -fx-background-radius: 20px; -fx-padding: 5 15;";
        String hoverStyle = "-fx-background-color: rgba(255,255,255,0.2); -fx-text-fill: white; -fx-font-size: 15px; -fx-cursor: hand; -fx-background-radius: 20px; -fx-padding: 5 15;";

        String[] navNames = {"启动", "下载", "服务器", "设置"};
        for (int i = 0; i < navNames.length; i++) {
            Button btn = new Button(navNames[i]);
            btn.setStyle(normalStyle);
            btn.setOnMouseEntered(e -> btn.setStyle(hoverStyle));
            btn.setOnMouseExited(e -> {
                if (btn.getUserData() == null || !(boolean) btn.getUserData()) btn.setStyle(normalStyle);
            });
            btn.setUserData(false);
            int index = i;
            btn.setOnAction(e -> switchTab(index));
            navBox.getChildren().add(btn);
        }

        Region rightSpacer = new Region();
        HBox.setHgrow(rightSpacer, Priority.ALWAYS);

        topBar.getChildren().addAll(logo, leftSpacer, navBox, rightSpacer);

        // ================= 左侧边栏 =================
        sidebar = new VBox(15);
        sidebar.setPrefWidth(260);
        sidebar.setPadding(new Insets(20));
        sidebar.setAlignment(Pos.TOP_CENTER);
        sidebar.setStyle("-fx-background-color: #ffffff;");

        HBox loginBtnBox = new HBox(10);
        loginBtnBox.setAlignment(Pos.CENTER);
        Button btnOffline = new Button("离线登录");
        btnOffline.setStyle("-fx-font-size: 11px; -fx-padding: 3 8; -fx-cursor: hand;");
        Button btnOnline = new Button("正版登录");
        btnOnline.setStyle("-fx-font-size: 11px; -fx-padding: 3 8; -fx-cursor: hand;");

        btnOffline.setOnAction(e -> {
            core.logout();
            updatePlayerDisplay();
        });
        btnOnline.setOnAction(e -> showMicrosoftLoginDialog());
        loginBtnBox.getChildren().addAll(btnOffline, btnOnline);

        // 头像框设置：固定大小，圆角，背景色
        avatarView = new ImageView();
        avatarView.setFitWidth(64);
        avatarView.setFitHeight(64);
        avatarView.setPreserveRatio(true);
        Rectangle clip = new Rectangle(64, 64);
        clip.setArcWidth(10);
        clip.setArcHeight(10);
        avatarView.setClip(clip);
        
        StackPane avatarContainer = new StackPane(avatarView);
        avatarContainer.setPrefSize(64, 64);
        avatarContainer.setMaxSize(64, 64);
        avatarContainer.setStyle("-fx-background-color: #f0f0f0; -fx-background-radius: 10;");

        sidebarPlayerName.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #333;");
        sidebarLoginStatus.setStyle("-fx-font-size: 13px; -fx-text-fill: #888;");

        offlineNameField.setPrefWidth(150);
        offlineNameField.setAlignment(Pos.CENTER);
        offlineNameField.setStyle("-fx-font-size: 14px; -fx-alignment: center;");
        offlineNameField.setVisible(false);

        // 回车保存
        offlineNameField.setOnAction(e -> {
            saveOfflineName();
            // 回车后让输入框失去焦点，UI 会自动切换显示
            sidebar.requestFocus();
        });
        
        // ★ 修复焦点跑偏问题：使用 Platform.runLater 推迟执行 UI 切换
        offlineNameField.focusedProperty().addListener((obs, oldVal, newVal) -> {
            if (!newVal) {
                Platform.runLater(this::saveOfflineName);
            }
        });

        HBox instanceBtns = new HBox(10);
        Button instanceSelectBtn = new Button("实例选择");
        Button instanceSettingsBtn = new Button("实例设置");
        instanceSelectBtn.setMaxWidth(Double.MAX_VALUE);
        instanceSettingsBtn.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(instanceSelectBtn, Priority.ALWAYS);
        HBox.setHgrow(instanceSettingsBtn, Priority.ALWAYS);

        instanceSelectBtn.setOnAction(e -> showInstanceSelectDialog());
        instanceSettingsBtn.setOnAction(e -> showInstanceSettingsDialog());
        instanceBtns.getChildren().addAll(instanceSelectBtn, instanceSettingsBtn);

        sidebar.getChildren().addAll(loginBtnBox, avatarContainer, sidebarPlayerName, offlineNameField, sidebarLoginStatus,
                new Region(), launchGameBtn, new Region(), instanceBtns);

        // ================= 右侧主内容区 =================
        contentStack = new StackPane();
        contentStack.setStyle("-fx-background-color: #eaf2f9;");
        HBox.setHgrow(contentStack, Priority.ALWAYS);

        homePage = buildHomePage();
        serversPage = buildServersPage();
        downloadPage = buildDownloadPage();
        settingsPage = buildSettingsPage();

        hideAllPages();

        contentStack.getChildren().addAll(homePage, serversPage, downloadPage, settingsPage);

        HBox mainLayout = new HBox(sidebar, contentStack);
        VBox root = new VBox(topBar, mainLayout);
        VBox.setVgrow(mainLayout, Priority.ALWAYS);

        Scene scene = new Scene(root, 1000, 720);
        scene.getRoot().setStyle("-fx-base: #f4f4f4; -fx-background: #ffffff; -fx-control-inner-background: #ffffff; -fx-text-fill: #333333;");

        stage.setTitle("极简 MC 启动器");
        stage.setScene(scene);
        stage.setMinWidth(900);
        stage.setMinHeight(640);
        stage.show();

        switchTab(0);
    }

    private void saveOfflineName() {
        if (!offlineNameField.isVisible()) return;
        String name = offlineNameField.getText().trim();
        if (name.isEmpty()) name = "Player";
        core.setPlayerName(name);
        updatePlayerDisplay();
    }

    private void switchTab(int index) {
        String normalStyle = "-fx-background-color: transparent; -fx-text-fill: rgba(255,255,255,0.8); -fx-font-size: 15px; -fx-cursor: hand; -fx-background-radius: 20px; -fx-padding: 5 15;";
        String activeStyle = "-fx-background-color: rgba(255,255,255,0.2); -fx-text-fill: white; -fx-font-size: 15px; -fx-cursor: hand; -fx-background-radius: 20px; -fx-padding: 5 15;";

        for (int i = 0; i < navBox.getChildren().size(); i++) {
            Button btn = (Button) navBox.getChildren().get(i);
            boolean isActive = (i == index);
            btn.setUserData(isActive);
            btn.setStyle(isActive ? activeStyle : normalStyle);
        }

        hideAllPages();

        if (index == 0) {
            sidebar.setVisible(true);
            sidebar.setManaged(true);
            showPage(homePage);
        } else {
            sidebar.setVisible(false);
            sidebar.setManaged(false);
            
            switch (index) {
                case 1 -> {
                    showPage(downloadPage);
                    if (categoryListView.getSelectionModel().getSelectedIndex() != 0) {
                        categoryListView.getSelectionModel().select(0);
                    }
                    switchDownloadCategory("minecraft");
                }
                case 2 -> showPage(serversPage);
                case 3 -> showPage(settingsPage);
            }
        }
    }

    private void hideAllPages() {
        homePage.setVisible(false); homePage.setManaged(false);
        serversPage.setVisible(false); serversPage.setManaged(false);
        downloadPage.setVisible(false); downloadPage.setManaged(false);
        settingsPage.setVisible(false); settingsPage.setManaged(false);
    }

    private void showPage(Node page) {
        page.setVisible(true);
        page.setManaged(true);
        page.toFront();
    }

    // ================= 弹窗类 =================
    private void showMicrosoftLoginDialog() {
        Stage dialog = new Stage();
        dialog.initOwner(stage);
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setTitle("微软正版登录");

        VBox box = new VBox(15);
        box.setPadding(new Insets(20));
        Label title = new Label("请按以下步骤完成登录：");
        title.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
        Label step1 = new Label("1. 打开浏览器访问: https://www.microsoft.com/link");
        Label step2 = new Label("2. 输入下面的设备代码");
        Label waiting = new Label("正在获取设备代码...");
        Label codeLabel = new Label("");
        codeLabel.setStyle("-fx-font-size: 30px; -fx-font-weight: bold; -fx-text-fill: #0057a5; -fx-letter-spacing: 8px;");

        Button copyBtn = new Button("复制代码");
        copyBtn.setDisable(true);
        Button openBtn = new Button("打开浏览器");
        openBtn.setDisable(true);
        Button cancelBtn = new Button("取消");

        HBox btns = new HBox(10, copyBtn, openBtn);
        btns.setAlignment(Pos.CENTER);
        box.getChildren().addAll(title, step1, step2, codeLabel, waiting, btns, cancelBtn);
        dialog.setScene(new Scene(box, 420, 320));
        dialog.show();

        final MicrosoftAuth.DeviceCode[] holder = new MicrosoftAuth.DeviceCode[1];

        new Thread(() -> {
            try {
                MicrosoftAuth.DeviceCode dc = core.requestDeviceCode();
                holder[0] = dc;
                Platform.runLater(() -> {
                    codeLabel.setText(dc.userCode());
                    waiting.setText("3. 登录成功后会自动检测（有效期 " + (dc.expiresIn() / 60) + " 分钟）");
                    copyBtn.setDisable(false);
                    openBtn.setDisable(false);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> waiting.setText("获取设备代码失败: " + ex.getMessage()));
            }
        }).start();

        copyBtn.setOnAction(e -> {
            if (holder[0] != null) {
                java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new java.awt.datatransfer.StringSelection(holder[0].userCode()), null);
                waiting.setText("已复制到剪贴板！");
            }
        });
        openBtn.setOnAction(e -> {
            if (holder[0] != null) {
                try { new ProcessBuilder("cmd", "/c", "start", holder[0].verifyUrl()).start(); } catch (Exception ignored) {}
            }
        });
        cancelBtn.setOnAction(e -> dialog.close());
        dialog.setOnCloseRequest(e -> {
            if (holder[0] != null) {
                core.pollDeviceLogin(holder[0], () -> Platform.runLater(this::updatePlayerDisplay));
            }
        });
    }

    private void showInstanceSelectDialog() {
        Stage dialog = new Stage();
        dialog.initOwner(stage);
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setTitle("选择游戏实例");

        VBox box = new VBox(15);
        box.setPadding(new Insets(20));
        ListView<String> list = new ListView<>();
        list.getItems().setAll(core.installedVersions());
        list.setPrefHeight(200);

        Button confirmBtn = new Button("确认选择");
        confirmBtn.setMaxWidth(Double.MAX_VALUE);
        confirmBtn.setOnAction(e -> {
            String selected = list.getSelectionModel().getSelectedItem();
            if (selected != null) {
                versionCombo.setValue(selected);
                currentVersionLabel.setText(selected);
                core.config().put("current_version", selected);
                dialog.close();
            }
        });
        box.getChildren().addAll(new Label("请选择要启动的版本："), list, confirmBtn);
        dialog.setScene(new Scene(box, 300, 300));
        dialog.showAndWait();
    }

    private void showInstanceSettingsDialog() {
        Stage dialog = new Stage();
        dialog.initOwner(stage);
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setTitle("实例设置");

        VBox box = new VBox(20);
        box.setPadding(new Insets(20));

        int currentMax = 2;
        try {
            String m = core.config().getString("max_memory", "2G").trim().toUpperCase();
            if (m.endsWith("G")) currentMax = Integer.parseInt(m.substring(0, m.length() - 1));
        } catch (Exception ignored) {}

        Label memLabel = new Label("最大内存: " + currentMax + "G");
        Slider memSlider = new Slider(1, 16, currentMax);
        memSlider.setShowTickLabels(true);
        memSlider.setShowTickMarks(true);
        memSlider.setMajorTickUnit(1);
        memSlider.setBlockIncrement(1);
        memSlider.valueProperty().addListener((obs, o, n) -> {
            int gb = Math.max(1, (int) Math.round(n.doubleValue()));
            memLabel.setText("最大内存: " + gb + "G");
            core.config().put("max_memory", gb + "G");
        });

        VBox memBox = new VBox(10, memLabel, memSlider);
        HBox toolRow = new HBox(10);
        toolRow.getChildren().addAll(
                new Button("安装 Java 21"){{ setOnAction(e -> core.installJava("21")); }},
                new Button("检查更新"){{ setOnAction(e -> core.checkUpdates()); }},
                new Button("打开数据目录"){{ setOnAction(e -> openPath(AppPaths.PYLAUNCHER_DIR)); }});

        box.getChildren().addAll(new Label("实例设置"), memBox, new Separator(), toolRow);
        dialog.setScene(new Scene(box, 500, 250));
        dialog.showAndWait();
    }

    // ================= 页面构建 =================
    private VBox buildHomePage() {
        VBox box = new VBox(20);
        box.setPadding(new Insets(40));
        box.setAlignment(Pos.TOP_CENTER);
        Label title = new Label("欢迎使用极简 MC 启动器");
        title.setStyle("-fx-font-size: 24px; -fx-font-weight: bold; -fx-text-fill: #0057a5;");
        box.getChildren().addAll(title, statusLabel);
        return box;
    }

    private VBox buildServersPage() {
        VBox box = new VBox(10);
        box.setPadding(new Insets(30));
        HBox form = new HBox(10);
        form.setAlignment(Pos.CENTER_LEFT);
        serverTypeMenu.getItems().setAll("paper", "purpur", "vanilla", "fabric", "forge");
        serverTypeMenu.setValue("paper");
        serverVersionEntry.setPromptText("如 1.21.1");

        Button downloadServerBtn = new Button("下载服务端");
        downloadServerBtn.setOnAction(e -> onInstallServer());
        Button refreshServerBtn = new Button("刷新列表");
        refreshServerBtn.setOnAction(e -> refreshServers());

        form.getChildren().addAll(new Label("类型"), serverTypeMenu, new Label("版本"), serverVersionEntry,
                new Label("内存"), serverMemEntry, downloadServerBtn, refreshServerBtn);

        serversList.setPrefHeight(90);
        serversList.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                String s = serversList.getSelectionModel().getSelectedItem();
                if (s != null) {
                    serverLogArea.appendText("> 启动服务器 " + s + "\n");
                    core.launchServer(s);
                }
            }
        });

        serverCommandField.setPromptText("输入服务器命令，回车发送...");
        serverCommandField.setOnAction(e -> {
            String cmd = serverCommandField.getText();
            if (!cmd.isBlank()) {
                serverLogArea.appendText("> " + cmd + "\n");
                core.sendServerCommand(cmd);
                serverCommandField.clear();
            }
        });
        serverLogArea.setEditable(false);
        VBox.setVgrow(serverLogArea, Priority.ALWAYS);
        box.getChildren().addAll(new Label("服务端下载与启动"), form, serversList, new Label("控制台日志"), serverLogArea, serverCommandField);
        return box;
    }

    private VBox buildDownloadPage() {
        HBox splitLayout = new HBox(0);
        splitLayout.setStyle("-fx-background-color: #eaf2f9;");
        splitLayout.setMaxWidth(Double.MAX_VALUE);
        splitLayout.setMaxHeight(Double.MAX_VALUE);

        VBox leftPanel = new VBox(10);
        leftPanel.setPrefWidth(220);
        leftPanel.setMinWidth(220);
        leftPanel.setMaxWidth(220);
        leftPanel.setMaxHeight(Double.MAX_VALUE);
        leftPanel.setStyle("-fx-background-color: #f8f9fa; -fx-border-color: #e0e0e0; -fx-border-width: 0 1 0 0;");
        leftPanel.setPadding(new Insets(20, 10, 20, 10));

        Label catTitle = new Label("下载分类");
        catTitle.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #333; -fx-padding: 0 0 10 10;");

        categoryListView = new ListView<>();
        categoryListView.getItems().addAll("minecraft", "整合包", "数据包", "资源包", "光影包", "世界");
        categoryListView.setStyle("-fx-background-color: transparent; -fx-control-inner-background: transparent; -fx-border-color: transparent; -fx-font-size: 14px;");
        
        categoryListView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("-fx-background-color: transparent;");
                } else {
                    setText(item);
                    if (isSelected()) {
                        setStyle("-fx-background-color: #0057a5; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px;");
                    } else {
                        setStyle("-fx-background-color: transparent; -fx-text-fill: #333333; -fx-font-size: 14px;");
                    }
                }
            }
        });
        VBox.setVgrow(categoryListView, Priority.ALWAYS);

        categoryListView.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) switchDownloadCategory(newVal);
        });

        leftPanel.getChildren().addAll(catTitle, categoryListView);

        rightDownloadStack = new StackPane();
        HBox.setHgrow(rightDownloadStack, Priority.ALWAYS);
        rightDownloadStack.setStyle("-fx-background-color: #eaf2f9;");
        rightDownloadStack.setMinSize(0, 0);
        rightDownloadStack.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        downloadViews.put("minecraft", wrapFull(buildMinecraftDownloadView()));
        downloadViews.put("整合包", wrapFull(buildModpackDownloadView()));
        downloadViews.put("数据包", wrapFull(buildModrinthCategoryView("热门数据包", "输入数据包名称...", "datapack", "搜索并安装数据包")));
        downloadViews.put("资源包", wrapFull(buildModrinthCategoryView("热门资源包", "输入资源包名称...", "resourcepack", "搜索并安装资源包")));
        downloadViews.put("光影包", wrapFull(buildModrinthCategoryView("热门光影包", "输入光影名称...", "shader", "搜索并安装光影")));
        downloadViews.put("世界", wrapFull(buildModrinthCategoryView("热门世界存档", "输入世界名称...", "world", "搜索并安装世界")));

        rightDownloadStack.getChildren().addAll(downloadViews.values());
        downloadViews.values().forEach(n -> {
            n.setVisible(false);
            n.setManaged(false);
        });

        splitLayout.getChildren().addAll(leftPanel, rightDownloadStack);

        VBox wrapper = new VBox(splitLayout);
        wrapper.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(splitLayout, Priority.ALWAYS);
        return wrapper;
    }

    private Node wrapFull(Node content) {
        StackPane pane = new StackPane(content);
        pane.setMinSize(0, 0);
        pane.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        StackPane.setAlignment(content, Pos.TOP_LEFT);
        if (content instanceof Region r) {
            r.setMaxWidth(Double.MAX_VALUE);
            r.setMaxHeight(Double.MAX_VALUE);
        }
        return pane;
    }

    private void switchDownloadCategory(String category) {
        downloadViews.values().forEach(n -> {
            n.setVisible(false);
            n.setManaged(false);
        });
        Node view = downloadViews.get(category);
        if (view != null) {
            view.setVisible(true);
            view.setManaged(true);
            view.toFront();
            
            if ("minecraft".equals(category) && !mcVersionsLoaded) {
                loadMinecraftVersions();
            }
        } else {
            System.err.println("[下载页] 未找到分类视图: " + category);
        }
    }

    // ================= Minecraft 下载特定视图 =================
    private VBox buildMinecraftDownloadView() {
        mcRightPane = new StackPane();
        
        // 视图1：版本列表
        mcVersionListView = new VBox(15);
        mcVersionListView.setPadding(new Insets(30));
        
        Label title = new Label("选择 Minecraft 版本");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: #0057a5;");
        
        mcVersionAccordion = new Accordion();
        mcVersionAccordion.setMaxWidth(Double.MAX_VALUE);
        mcVersionAccordion.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(mcVersionAccordion, Priority.ALWAYS);
        
        TitledPane loadingPane = new TitledPane("正在获取版本...", new Label("请稍候..."));
        loadingPane.setCollapsible(false);
        mcVersionAccordion.getPanes().add(loadingPane);
        mcVersionAccordion.setExpandedPane(loadingPane);

        mcVersionListView.getChildren().addAll(title, mcVersionAccordion);
        
        // 视图2：加载器选择
        mcLoaderSelectView = new VBox(15);
        mcLoaderSelectView.setPadding(new Insets(30));
        mcLoaderSelectView.setStyle("-fx-background-color: #eaf2f9;");
        mcLoaderSelectView.setVisible(false);
        
        mcRightPane.getChildren().addAll(mcVersionListView, mcLoaderSelectView);

        VBox wrapper = new VBox(mcRightPane);
        wrapper.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(mcRightPane, Priority.ALWAYS);
        return wrapper;
    }

    private void loadMinecraftVersions() {
        mcVersionsLoaded = true;
        uiExecutor.submit(() -> {
            try {
                List<Api.VersionInfo> allVersions = Api.mojangVersions();
                List<Api.VersionInfo> release = new ArrayList<>();
                List<Api.VersionInfo> snapshot = new ArrayList<>();
                List<Api.VersionInfo> old = new ArrayList<>();
                List<Api.VersionInfo> aprilFools = new ArrayList<>();

                for (Api.VersionInfo v : allVersions) {
                    String cat = Api.categoryOf(v);
                    switch (cat) {
                        case "正式版" -> release.add(v);
                        case "预览版" -> snapshot.add(v);
                        case "远古版" -> old.add(v);
                        case "愚人节版" -> aprilFools.add(v);
                    }
                }

                Platform.runLater(() -> {
                    if (mcVersionAccordion == null) return;
                    mcVersionAccordion.getPanes().clear();
                    TitledPane releasePane = createVersionPane("正式版", release);
                    TitledPane snapshotPane = createVersionPane("预览版", snapshot);
                    TitledPane oldPane = createVersionPane("远古版", old);
                    TitledPane aprilPane = createVersionPane("愚人节版", aprilFools);

                    mcVersionAccordion.getPanes().addAll(releasePane, snapshotPane, oldPane, aprilPane);
                    if (!release.isEmpty()) {
                        mcVersionAccordion.setExpandedPane(releasePane);
                    }
                });
            } catch (Exception e) {
                mcVersionsLoaded = false;
                Platform.runLater(() -> {
                    if (mcVersionAccordion != null) {
                        mcVersionAccordion.getPanes().clear();
                        TitledPane errorPane = new TitledPane("加载失败", new Label("获取版本失败: " + e.getMessage()));
                        errorPane.setCollapsible(false);
                        mcVersionAccordion.getPanes().add(errorPane);
                        mcVersionAccordion.setExpandedPane(errorPane);
                    }
                });
            }
        });
    }

    private TitledPane createVersionPane(String title, List<Api.VersionInfo> versions) {
        VBox listBox = new VBox(2);
        listBox.setPadding(new Insets(5));
        
        ScrollPane scroll = new ScrollPane(listBox);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent; -fx-control-inner-background: transparent;");
        scroll.setPrefHeight(200);

        TitledPane pane = new TitledPane(title + " (" + versions.size() + ")", scroll);
        pane.setAnimated(true);

        for (Api.VersionInfo v : versions) {
            HBox row = new HBox(10);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(8, 12, 8, 12));
            row.setStyle("-fx-background-color: white; -fx-background-radius: 6; -fx-border-radius: 6; -fx-border-color: #e0e0e0; -fx-cursor: hand;");
            
            Label name = new Label(v.id());
            name.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #333;");
            
            Label type = new Label(v.type());
            type.setStyle("-fx-font-size: 12px; -fx-text-fill: #888;");
            HBox.setHgrow(type, Priority.ALWAYS);
            
            Button selectBtn = new Button("选择");
            selectBtn.setStyle("-fx-font-size: 12px; -fx-padding: 3 8;");
            
            row.getChildren().addAll(name, type, selectBtn);
            
            Runnable selectAction = () -> {
                selectedMcVersion = v.id();
                showLoaderSelectView(v.id());
            };
            row.setOnMouseClicked(e -> selectAction.run());
            selectBtn.setOnAction(e -> selectAction.run());
            
            row.setOnMouseEntered(e -> row.setStyle("-fx-background-color: #f0f7ff; -fx-background-radius: 6; -fx-border-radius: 6; -fx-border-color: #a0c4f0; -fx-cursor: hand;"));
            row.setOnMouseExited(e -> row.setStyle("-fx-background-color: white; -fx-background-radius: 6; -fx-border-radius: 6; -fx-border-color: #e0e0e0; -fx-cursor: hand;"));

            listBox.getChildren().add(row);
        }
        return pane;
    }

    // 显示加载器选择视图
    private void showLoaderSelectView(String version) {
        mcLoaderSelectView.getChildren().clear();
        
        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);
        Button backBtn = new Button("⬅ 返回");
        backBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #0057a5; -fx-cursor: hand; -fx-font-size: 14px;");
        backBtn.setOnAction(e -> {
            mcLoaderSelectView.setVisible(false);
            mcVersionListView.setVisible(true);
        });
        
        Label verLabel = new Label(version);
        verLabel.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: #333; -fx-border-color: #ccc; -fx-border-radius: 4; -fx-padding: 4 10;");
        
        header.getChildren().addAll(backBtn, verLabel);
        mcLoaderSelectView.getChildren().add(header);

        String[] loaders = {"原版 (Vanilla)", "Forge", "NeoForge", "Fabric", "Quilt", "OptiFine"};
        for (String loaderName : loaders) {
            HBox row = new HBox(15);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(15, 20, 15, 20));
            row.setStyle("-fx-background-color: white; -fx-background-radius: 8; -fx-border-radius: 8; -fx-border-color: #e0e0e0; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.05), 10, 0, 0, 4); -fx-cursor: hand;");
            
            Label icon = new Label("📦");
            icon.setStyle("-fx-font-size: 24px;");
            
            Label name = new Label(loaderName);
            name.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #333;");
            HBox.setHgrow(name, Priority.ALWAYS);
            
            Label action = new Label("可以添加 ➔");
            action.setStyle("-fx-font-size: 14px; -fx-text-fill: #888;");

            row.getChildren().addAll(icon, name, action);
            
            row.setOnMouseEntered(e -> row.setStyle("-fx-background-color: #f5f9ff; -fx-background-radius: 8; -fx-border-radius: 8; -fx-border-color: #a0c4f0; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.1), 10, 0, 0, 4); -fx-cursor: hand;"));
            row.setOnMouseExited(e -> row.setStyle("-fx-background-color: white; -fx-background-radius: 8; -fx-border-radius: 8; -fx-border-color: #e0e0e0; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.05), 10, 0, 0, 4); -fx-cursor: hand;"));
            
            row.setOnMouseClicked(e -> {
                String realLoader = loaderName.equals("原版 (Vanilla)") ? "vanilla" : loaderName.toLowerCase();
                startDownload(version, realLoader);
            });
            
            mcLoaderSelectView.getChildren().add(row);
        }

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        mcLoaderSelectView.getChildren().add(spacer);

        downloadProgressBar = new ProgressBar(0);
        downloadProgressBar.setMaxWidth(Double.MAX_VALUE);
        
        downloadStatusLabel = new Label("就绪");
        downloadStatusLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: #666;");

        downloadLogArea = new TextArea();
        downloadLogArea.setEditable(false);
        downloadLogArea.setPrefHeight(120);

        downloadBtn = new Button("开始下载");
        downloadBtn.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-padding: 10; -fx-background-color: #0057a5; -fx-text-fill: white; -fx-background-radius: 5;");
        downloadBtn.setMaxWidth(Double.MAX_VALUE);
        
        cancelDownloadBtn = new Button("取消下载");
        cancelDownloadBtn.setDisable(true);
        cancelDownloadBtn.setStyle("-fx-font-size: 14px; -fx-padding: 10; -fx-text-fill: #d32f2f;");
        cancelDownloadBtn.setMaxWidth(Double.MAX_VALUE);
        cancelDownloadBtn.setOnAction(e -> {
            core.cancelDownload();
            downloadLogArea.appendText("已请求取消下载...\n");
            cancelDownloadBtn.setDisable(true);
            downloadBtn.setDisable(false);
        });

        HBox buttonRow = new HBox(10);
        buttonRow.getChildren().addAll(downloadBtn, cancelDownloadBtn);

        mcLoaderSelectView.getChildren().addAll(downloadProgressBar, downloadStatusLabel, downloadLogArea, buttonRow);

        mcVersionListView.setVisible(false);
        mcLoaderSelectView.setVisible(true);
        mcLoaderSelectView.toFront();
    }

    // 真正触发下载
    private void startDownload(String version, String loader) {
        downloadProgressBar.setProgress(0);
        downloadBtn.setDisable(true);
        cancelDownloadBtn.setDisable(false);
        downloadStatusLabel.setText("正在下载...");
        downloadLogArea.clear();

        if ("vanilla".equals(loader)) {
            downloadLogArea.appendText("开始下载 Minecraft " + version + " 原版...\n");
            core.installVanilla(version);
        } else if ("optifine".equals(loader)) {
            downloadLogArea.appendText("OptiFine 需手动安装\n");
            downloadBtn.setDisable(false);
            cancelDownloadBtn.setDisable(true);
        } else {
            downloadLogArea.appendText("开始下载 " + loader + " + Minecraft " + version + "...\n");
            core.installLoader(loader, version);
        }
    }

    private VBox buildModrinthCategoryView(String title, String prompt, String projectType, String btnText) {
        VBox box = new VBox(12);
        box.setPadding(new Insets(30));
        box.setMaxWidth(Double.MAX_VALUE);
        box.setMaxHeight(Double.MAX_VALUE);

        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: #0057a5;");

        TextField searchField = new TextField();
        searchField.setPromptText(prompt);
        Button searchBtn = new Button(btnText);
        HBox searchRow = new HBox(10, searchField, searchBtn);
        HBox.setHgrow(searchField, Priority.ALWAYS);

        VBox resultList = new VBox(6);
        resultList.setPadding(new Insets(4));
        ScrollPane scroll = new ScrollPane(resultList);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent; -fx-control-inner-background: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        Runnable doSearch = () -> {
            String q = searchField.getText().trim();
            if (q.isEmpty()) return;
            resultList.getChildren().setAll(new Label("搜索中..."));
            loadModrinthData(resultList, q, projectType, true);
        };

        searchBtn.setOnAction(e -> doSearch.run());
        searchField.setOnAction(e -> doSearch.run());

        box.getChildren().addAll(titleLabel, new Separator(), searchRow, scroll);

        Platform.runLater(() -> loadModrinthData(resultList, "", projectType, false));

        return box;
    }

    private void loadModrinthData(VBox resultList, String query, String projectType, boolean isSearch) {
        uiExecutor.submit(() -> {
            try {
                JsonNode data = Api.modrinthSearchByType(query, projectType);
                JsonNode hits = data.get("hits");
                List<JsonNode> list = new ArrayList<>();
                if (hits != null) hits.forEach(list::add);
                
                Platform.runLater(() -> {
                    resultList.getChildren().clear();
                    if (list.isEmpty()) {
                        resultList.getChildren().add(new Label("未找到结果"));
                        return;
                    }
                    for (JsonNode m : list) {
                        String name = m.has("title") ? m.get("title").asText() : "?";
                        String slug = m.has("slug") ? m.get("slug").asText() : name;
                        long downloads = m.has("downloads") ? m.get("downloads").asLong() : 0;

                        HBox row = new HBox(10);
                        row.setAlignment(Pos.CENTER_LEFT);
                        row.setPadding(new Insets(8, 12, 8, 12));
                        row.setStyle("-fx-background-color: white; -fx-background-radius: 6; -fx-border-radius: 6; -fx-border-color: #e0e0e0;");

                        Label nameLabel = new Label(name);
                        nameLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #333;");
                        Label info = new Label(downloads + " 次下载");
                        info.setStyle("-fx-font-size: 12px; -fx-text-fill: #888;");
                        VBox textBox = new VBox(2, nameLabel, info);
                        HBox.setHgrow(textBox, Priority.ALWAYS);

                        Button install = new Button("安装");
                        install.setOnAction(e -> {
                            install.setDisable(true);
                            install.setText("下载中...");
                            
                            switch (projectType) {
                                case "shader" -> core.installShader(name);
                                case "datapack" -> core.installDatapack(name);
                                case "resourcepack" -> core.installResourcepack(name);
                                case "world" -> core.installWorld(name);
                                default -> {}
                            }
                            
                            new Thread(() -> {
                                try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
                                Platform.runLater(() -> {
                                    install.setDisable(false);
                                    install.setText("安装");
                                });
                            }).start();
                        });

                        row.getChildren().addAll(textBox, install);
                        resultList.getChildren().add(row);
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> resultList.getChildren().setAll(new Label("获取失败: " + e.getMessage())));
            }
        });
    }

    private VBox buildModpackDownloadView() {
        VBox box = new VBox(15);
        box.setPadding(new Insets(30));

        Label title = new Label("整合包");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: #0057a5;");

        Button importBtn = new Button("从本地导入 .mrpack");
        importBtn.setOnAction(e -> {
            javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
            fc.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("Modrinth 整合包", "*.mrpack"));
            java.io.File f = fc.showOpenDialog(stage);
            if (f != null) core.importModpack(f.getAbsolutePath());
        });

        VBox onlineBox = buildModrinthCategoryView("热门整合包", "输入整合包名称...", "modpack", "搜索并下载");
        box.getChildren().addAll(title, new Separator(), importBtn, new Separator(), onlineBox);
        return box;
    }

    // ================= 设置页面 =================
    private VBox buildSettingsPage() {
        VBox box = new VBox(20);
        box.setPadding(new Insets(30));
        box.setStyle("-fx-background-color: #eaf2f9;");

        Label title = new Label("启动器设置");
        title.setStyle("-fx-font-size: 24px; -fx-font-weight: bold; -fx-text-fill: #0057a5;");
        box.getChildren().add(title);

        // 1. 网络设置
        VBox netCard = new VBox(15);
        netCard.setPadding(new Insets(20));
        netCard.setStyle("-fx-background-color: white; -fx-background-radius: 8; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.05), 10, 0, 0, 4);");

        Label netTitle = new Label("网络设置");
        netTitle.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #333;");

        HBox netRow = new HBox(10);
        netRow.setAlignment(Pos.CENTER_LEFT);
        Label sourceLabel = new Label("下载源:");
        sourceLabel.setStyle("-fx-font-size: 14px;");
        
        downloadSourceCombo = new ComboBox<>();
        downloadSourceCombo.getItems().addAll("bmcl", "official");
        String currentSource = core.config().getString("download_source", "bmcl");
        downloadSourceCombo.setValue(currentSource);
        
        downloadSourceCombo.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item.equals("bmcl") ? "BMCLAPI 镜像源 (推荐国内)" : "Mojang 官方源 (可能较慢)");
                }
            }
        });
        downloadSourceCombo.setButtonCell(downloadSourceCombo.getCellFactory().call(null));

        downloadSourceCombo.setOnAction(e -> {
            String val = downloadSourceCombo.getValue();
            core.config().put("download_source", val);
            core.config().save(AppPaths.CONFIG_FILE);
        });

        netRow.getChildren().addAll(sourceLabel, downloadSourceCombo);
        netCard.getChildren().addAll(netTitle, new Separator(), netRow);

        // 2. 内存设置
        VBox memCard = new VBox(15);
        memCard.setPadding(new Insets(20));
        memCard.setStyle("-fx-background-color: white; -fx-background-radius: 8; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.05), 10, 0, 0, 4);");

        Label memTitle = new Label("游戏内存设置");
        memTitle.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #333;");

        int currentMax = 2;
        try {
            String m = core.config().getString("max_memory", "2G").trim().toUpperCase();
            if (m.endsWith("G")) currentMax = Integer.parseInt(m.substring(0, m.length() - 1));
        } catch (Exception ignored) {}

        maxMemLabel = new Label("最大内存: " + currentMax + "G");
        maxMemSlider = new Slider(1, 32, currentMax);
        maxMemSlider.setShowTickLabels(true);
        maxMemSlider.setShowTickMarks(true);
        maxMemSlider.setMajorTickUnit(4);
        maxMemSlider.setMinorTickCount(3);
        maxMemSlider.setBlockIncrement(1);
        maxMemSlider.valueProperty().addListener((obs, o, n) -> {
            int gb = Math.max(1, (int) Math.round(n.doubleValue()));
            maxMemLabel.setText("最大内存: " + gb + "G");
            core.config().put("max_memory", gb + "G");
            core.config().save(AppPaths.CONFIG_FILE);
        });

        int currentMin = 1;
        try {
            String m = core.config().getString("min_memory", "1G").trim().toUpperCase();
            if (m.endsWith("G")) currentMin = Integer.parseInt(m.substring(0, m.length() - 1));
        } catch (Exception ignored) {}

        minMemLabel = new Label("初始内存: " + currentMin + "G");
        minMemSlider = new Slider(1, 16, currentMin);
        minMemSlider.setShowTickLabels(true);
        minMemSlider.setShowTickMarks(true);
        minMemSlider.setMajorTickUnit(4);
        minMemSlider.setMinorTickCount(3);
        minMemSlider.setBlockIncrement(1);
        minMemSlider.valueProperty().addListener((obs, o, n) -> {
            int gb = Math.max(1, (int) Math.round(n.doubleValue()));
            minMemLabel.setText("初始内存: " + gb + "G");
            core.config().put("min_memory", gb + "G");
            core.config().save(AppPaths.CONFIG_FILE);
        });

        memCard.getChildren().addAll(memTitle, new Separator(), maxMemLabel, maxMemSlider, minMemLabel, minMemSlider);

        // 3. 启动器工具
        VBox toolCard = new VBox(15);
        toolCard.setPadding(new Insets(20));
        toolCard.setStyle("-fx-background-color: white; -fx-background-radius: 8; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.05), 10, 0, 0, 4);");

        Label toolTitle = new Label("启动器工具");
        toolTitle.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #333;");

        HBox toolRow = new HBox(10);
        Button btnInstallJava = new Button("安装 Java 21");
        btnInstallJava.setOnAction(e -> core.installJava("21"));
        
        Button btnOpenDir = new Button("打开数据目录");
        btnOpenDir.setOnAction(e -> openPath(AppPaths.PYLAUNCHER_DIR));
        
        Button btnCheckUpdate = new Button("检查更新");
        btnCheckUpdate.setOnAction(e -> core.checkUpdates());

        toolRow.getChildren().addAll(btnInstallJava, btnOpenDir, btnCheckUpdate);
        toolCard.getChildren().addAll(toolTitle, new Separator(), toolRow);

        ScrollPane scroll = new ScrollPane(new VBox(15, netCard, memCard, toolCard));
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent; -fx-control-inner-background: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        box.getChildren().add(scroll);
        return box;
    }

    // ================= 逻辑控制 =================

    private void onInstallServer() {
        String version = serverVersionEntry.getText().trim();
        if (version.isBlank()) return;
        String mem = serverMemEntry.getText().trim().isEmpty() ? "2G" : serverMemEntry.getText().trim();
        core.installServer(serverTypeMenu.getValue(), version, mem, mem);
    }

    private void refreshVersions() {
        List<String> versions = core.installedVersions();
        versionCombo.getItems().setAll(versions);
        if (!versions.isEmpty()) {
            String cur = core.config().getString("current_version");
            versionCombo.setValue(versions.contains(cur) ? cur : versions.get(0));
            currentVersionLabel.setText(versionCombo.getValue());
        }
        versionCombo.valueProperty().addListener((obs, o, n) -> currentVersionLabel.setText(n));
    }

    private void refreshServers() {
        serversList.getItems().setAll(core.installedServers());
    }

    private void updatePlayerDisplay() {
        boolean loggedIn = core.isLoggedIn();
        String rawName = core.playerDisplay();
        String displayName = rawName.replaceAll("[（(].*?[)）]", "").trim();
        if (displayName.isEmpty()) displayName = "Player";

        if (loggedIn) {
            sidebarPlayerName.setText(displayName);
            sidebarPlayerName.setVisible(true);
            offlineNameField.setVisible(false);
            sidebarLoginStatus.setText("正版验证");
        } else {
            sidebarPlayerName.setVisible(false);
            offlineNameField.setVisible(true);
            offlineNameField.setText(displayName);
            sidebarLoginStatus.setText("离线验证");
        }

        // 使用 Crafatar API 获取头像，支持正版与离线名
        String avatarUrl = "https://api.mcheads.org/head/" + displayName + "/64";
        try {
            Image image = new Image(avatarUrl, true);
            avatarView.setImage(image);
        } catch (Exception ignored) {}
    }

    private void openPath(Path path) {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) new ProcessBuilder("explorer", path.toString()).start();
            else if (os.contains("mac")) new ProcessBuilder("open", path.toString()).start();
            else new ProcessBuilder("xdg-open", path.toString()).start();
        } catch (Exception e) {
            if (downloadLogArea != null) downloadLogArea.appendText("无法打开: " + e.getMessage() + "\n");
        }
    }
}