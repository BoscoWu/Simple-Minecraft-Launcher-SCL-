package com.simplemc.launcher.ui.page;

import com.simplemc.launcher.core.AccountManager;
import com.simplemc.launcher.core.GameLauncher;
import com.simplemc.launcher.core.InstanceManager;
import com.simplemc.launcher.core.JavaManager;
import com.simplemc.launcher.core.VersionManager;
import com.simplemc.launcher.ui.MainWindow;
import com.simplemc.launcher.ui.Theme;
import com.simplemc.launcher.util.Config;
import com.simplemc.launcher.util.Constants;
import com.simplemc.launcher.util.Log;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 启动页：账户信息、实例选择、启动游戏。
 */
public final class HomePage {

    private final MainWindow window;
    private final Config config;
    private final VersionManager versionManager;
    private final GameLauncher gameLauncher;

    private final BorderPane root = new BorderPane();
    private final Label playerName = new Label();
    private TextField playerNameField;
    private final Label playerStatus = new Label();
    private final ImageView avatar = new ImageView();
    private final Label versionLabel = new Label();
    private final Label welcomeTitle = new Label("欢迎使用极简 MC 启动器");
    private final Label versionInfo = new Label();
    private final Label gameDirInfo = new Label();
    private final Button launchBtn = new Button("启动游戏");

    private AccountManager.Account account;
    private volatile boolean gameRunning = false;

    public HomePage(MainWindow window, Config config) {
        this.window = window;
        this.config = config;
        this.versionManager = new VersionManager(null);
        this.gameLauncher = new GameLauncher(Constants.MC_DIR);
        build();
    }

    public BorderPane root() {
        return root;
    }

    private void build() {
        // ---------- 左侧边栏 ----------
        VBox sidebar = new VBox(8);
        sidebar.getStyleClass().add("sidebar");
        sidebar.setAlignment(Pos.TOP_CENTER);

        HBox loginBtns = new HBox(8);
        loginBtns.setAlignment(Pos.CENTER);
        Button offlineBtn = new Button("离线");
        offlineBtn.getStyleClass().add("btn-primary");
        offlineBtn.setOnAction(e -> useOfflineName());
        Button premiumBtn = new Button("正版登录");
        premiumBtn.getStyleClass().add("btn-primary");
        premiumBtn.setOnAction(e -> doPremiumLogin());
        loginBtns.getChildren().addAll(offlineBtn, premiumBtn);
        sidebar.getChildren().add(loginBtns);

        // 头像
        StackPane avatarPane = new StackPane();
        avatarPane.setPrefSize(96, 96);
        avatarPane.setMaxSize(96, 96);
        avatar.setFitWidth(96);
        avatar.setFitHeight(96);
        avatar.setPreserveRatio(true);
        avatarPane.getChildren().add(avatar);
        VBox.setMargin(avatarPane, new Insets(12, 0, 4, 0));
        sidebar.getChildren().add(avatarPane);

        // 玩家名输入框（离线模式下可直接编辑）
        playerNameField = new TextField(config.getString("username"));
        playerNameField.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;"
                + "-fx-alignment: center; -fx-pref-width: 180;");
        playerNameField.setPromptText("输入玩家名");
        // 回车或失焦时保存名字并刷新头像
        playerNameField.setOnAction(e -> applyPlayerName());
        playerNameField.focusedProperty().addListener((obs, was, now) -> {
            if (!now) applyPlayerName();
        });
        sidebar.getChildren().add(playerNameField);

        playerStatus.getStyleClass().add("player-status");
        sidebar.getChildren().add(playerStatus);

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        sidebar.getChildren().add(spacer);

        // 实例按钮
        HBox instBtns = new HBox(8);
        instBtns.setAlignment(Pos.CENTER);
        Button instSelect = new Button("实例选择");
        instSelect.getStyleClass().add("btn-primary");
        instSelect.setOnAction(e -> showInstancePicker());
        Button instSetting = new Button("实例设置");
        instSetting.getStyleClass().add("btn-primary");
        instSetting.setOnAction(e -> window.switchTo("设置"));
        instBtns.getChildren().addAll(instSelect, instSetting);
        sidebar.getChildren().add(instBtns);

        // 当前版本
        versionLabel.getStyleClass().add("muted");
        versionLabel.setPadding(new Insets(8, 0, 6, 0));
        sidebar.getChildren().add(versionLabel);

        // 启动按钮
        launchBtn.getStyleClass().add("btn-launch");
        launchBtn.setMaxWidth(Double.MAX_VALUE);
        launchBtn.setOnAction(e -> doLaunch());
        sidebar.getChildren().add(launchBtn);

        root.setLeft(sidebar);

        // ---------- 右侧内容 ----------
        VBox content = new VBox(6);
        content.setAlignment(Pos.TOP_CENTER);
        content.setPadding(new Insets(60, 20, 20, 20));

        welcomeTitle.setStyle("-fx-font-size: 26px; -fx-font-weight: bold; -fx-text-fill: "
                + Theme.PRIMARY + ";");
        versionInfo.getStyleClass().add("muted");
        gameDirInfo.getStyleClass().add("muted");

        content.getChildren().addAll(welcomeTitle, versionInfo, gameDirInfo);
        root.setCenter(content);

        refreshAccount();
        refreshVersionLabel();
        versionInfo.setText("版本: " + Constants.VERSION);
        gameDirInfo.setText("游戏目录: " + Constants.MC_DIR);
    }

    /** 刷新账户显示 */
    public void refreshAccount() {
        String name = config.getString("username");
        if (name == null || name.isBlank()) name = "Steve";
        String mode = config.getString("auth_mode");
        account = AccountManager.offline(name);
        account.kind = mode.isBlank() ? "离线" : mode;

        if (playerNameField != null && !playerNameField.isFocused()) {
            playerNameField.setText(name);
        }
        playerStatus.setText("正版验证".equals(mode) ? "正版验证" : "离线验证");
        loadAvatar(name);
    }

    /** 把输入框里的名字应用为离线玩家名 */
    private void applyPlayerName() {
        if (playerNameField == null) return;
        String name = playerNameField.getText() == null
                ? "" : playerNameField.getText().trim();
        if (name.isBlank()) {
            // 空名字恢复成当前已保存的
            playerNameField.setText(config.getString("username"));
            return;
        }
        // 合法的 Minecraft 名字：3-16 位字母数字下划线
        if (!name.matches("[A-Za-z0-9_]{1,16}")) {
            Alert a = new Alert(Alert.AlertType.WARNING);
            a.setTitle("名字不合法");
            a.setHeaderText(null);
            a.setContentText("玩家名只能包含字母、数字和下划线，长度 1-16。");
            a.initOwner(window.stage());
            a.showAndWait();
            playerNameField.setText(config.getString("username"));
            return;
        }
        String current = config.getString("username");
        if (name.equals(current) && "离线".equals(config.getString("auth_mode"))) {
            return;
        }
        account = AccountManager.offline(name);
        config.set("username", name).set("auth_mode", "离线").save();
        refreshAccount();
        Log.info("离线玩家名已设为: " + name);
    }

    private void loadAvatar(String name) {
        // 先用占位图
        avatar.setImage(makePlaceholder());
        CompletableFuture.runAsync(() -> {
            String url = "https://api.mcheads.org/head/" + name + "/256";
            try {
                HttpClient c = HttpClient.newBuilder()
                        .connectTimeout(java.time.Duration.ofSeconds(10)).build();
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .header("User-Agent", Constants.USER_AGENT)
                        .timeout(java.time.Duration.ofSeconds(10))
                        .GET().build();
                HttpResponse<byte[]> resp = c.send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (resp.statusCode() == 200) {
                    Image img = new Image(new java.io.ByteArrayInputStream(resp.body()),
                            96, 96, true, true);
                    Platform.runLater(() -> avatar.setImage(img));
                }
            } catch (Exception e) {
                Log.warn("头像加载失败: " + e.getMessage());
            }
        });
    }

    private Image makePlaceholder() {
        // 生成一个简单的灰色头像占位
        javafx.scene.image.WritableImage img =
                new javafx.scene.image.WritableImage(96, 96);
        var writer = img.getPixelWriter();
        for (int y = 0; y < 96; y++) {
            for (int x = 0; x < 96; x++) {
                boolean dark = (x / 12 + y / 12) % 2 == 0;
                writer.setColor(x, y, dark ? Color.web("#b8b8b8") : Color.web("#a0a0a0"));
            }
        }
        return img;
    }

    private void refreshVersionLabel() {
        String cur = config.getString("current_version");
        if (cur == null || cur.isBlank()) {
            versionLabel.setText("未选择版本");
            return;
        }
        // 优先显示实例的可读名（如 "1.21.1 - Fabric 0.20.1"）
        try {
            InstanceManager im = new InstanceManager(Constants.MC_DIR);
            String display = im.displayName(cur);
            versionLabel.setText(display == null || display.isBlank() ? cur : display);
        } catch (Exception e) {
            versionLabel.setText(cur);
        }
    }

    // ---------------- 登录 ----------------

    /** 点「离线」按钮：切回离线模式，并把焦点放到名字输入框 */
    private void useOfflineName() {
        config.set("auth_mode", "离线").save();
        refreshAccount();
        if (playerNameField != null) {
            playerNameField.requestFocus();
            playerNameField.selectAll();
        }
        Log.info("已切换为离线模式");
    }

    private void doPremiumLogin() {
        CompletableFuture.runAsync(() -> {
            try {
                AccountManager.DeviceCode dc = AccountManager.requestDeviceCode();
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.INFORMATION);
                    alert.setTitle("Microsoft 正版登录");
                    alert.setHeaderText("请在浏览器中完成登录");
                    alert.setContentText(
                            "1. 打开网址: " + dc.verificationUri() + "\n"
                            + "2. 输入代码: " + dc.userCode() + "\n\n"
                            + "输入完成后点击「确定」，程序会自动检测登录状态。");
                    alert.initOwner(window.stage());
                    alert.showAndWait();

                    CompletableFuture.runAsync(() -> {
                        try {
                            AccountManager.Account acc =
                                    AccountManager.loginWithDeviceCode(dc, () -> false);
                            Platform.runLater(() -> {
                                account = acc;
                                config.set("username", acc.username)
                                        .set("auth_mode", "正版").save();
                                refreshAccount();
                                Alert ok = new Alert(Alert.AlertType.INFORMATION);
                                ok.setTitle("登录成功");
                                ok.setHeaderText(null);
                                ok.setContentText("欢迎, " + acc.username);
                                ok.initOwner(window.stage());
                                ok.showAndWait();
                            });
                        } catch (Exception ex) {
                            showError("登录失败", ex.getMessage());
                        }
                    });
                });
            } catch (Exception e) {
                showError("获取设备码失败", e.getMessage());
            }
        });
    }

    // ---------------- 启动游戏 ----------------

    private void doLaunch() {
        if (gameRunning) {
            Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                    "游戏正在运行，是否强制停止？", ButtonType.YES, ButtonType.NO);
            a.initOwner(window.stage());
            a.showAndWait().ifPresent(bt -> {
                if (bt == ButtonType.YES) {
                    gameLauncher.stop();
                    setGameRunning(false);
                }
            });
            return;
        }

        String versionId = config.getString("current_version");
        if (versionId == null || versionId.isBlank()) {
            showError("无法启动", "请先在「下载」页面安装一个游戏版本");
            return;
        }

        // 选 Java
        int required = JavaManager.requiredJavaMajor(
                new com.simplemc.launcher.core.InstanceManager(Constants.MC_DIR)
                        .resolveBaseVersion(versionId));
        List<JavaManager.JavaInfo> javas = JavaManager.detectAll();
        JavaManager.JavaInfo java = JavaManager.pickBest(javas, required);
        if (java == null) {
            showError("无法启动", "没有找到可用的 Java，请先在「设置」中安装 Java");
            return;
        }

        GameLauncher.LaunchOptions opt = new GameLauncher.LaunchOptions();
        opt.versionId = versionId;
        opt.javaPath = java.path();
        opt.username = account != null ? account.username : config.getString("username");
        opt.uuid = account != null ? account.uuid
                : AccountManager.offlineUuid(opt.username);
        opt.accessToken = account != null && account.isPremium()
                ? account.accessToken : "0";
        opt.maxMemMb = parseIntSafe(config.getString("max_mem"), 2048);
        opt.minMemMb = parseIntSafe(config.getString("min_mem"), 1024);
        opt.jvmArgs = config.getString("jvm_args");
        opt.gameArgs = config.getString("game_args");
        opt.windowTitle = config.getString("window_title");

        window.setProgress("正在启动 " + versionId + " ...", 0.3, "");
        CompletableFuture.runAsync(() -> {
            try {
                Process p = gameLauncher.launch(opt);
                Platform.runLater(() -> {
                    setGameRunning(true);
                    window.setProgress("游戏运行中", 1.0, "");
                    window.setStatus("游戏运行中");
                });
                int code = p.waitFor();
                Platform.runLater(() -> {
                    setGameRunning(false);
                    window.setProgress("游戏已退出 (code " + code + ")", 0, "0%");
                    window.setStatus("就绪");
                });
            } catch (Exception e) {
                Log.error("启动失败", e);
                Platform.runLater(() -> {
                    window.setProgress("启动失败", 0, "0%");
                    showError("启动失败", e.getMessage());
                });
            }
        });
    }

    private void setGameRunning(boolean running) {
        gameRunning = running;
        launchBtn.setText(running ? "停止游戏" : "启动游戏");
    }

    // ---------------- 实例选择 ----------------

    /**
     * 实例选择：覆盖整个启动器界面。
     * 左侧「文件夹列表」+ 右侧「实例列表（可搜索）」，沿用本项目的蓝色主题。
     */
    private void showInstancePicker() {
        javafx.scene.layout.BorderPane overlay = new javafx.scene.layout.BorderPane();
        overlay.setStyle("-fx-background-color: " + Theme.BG + ";");
        overlay.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        // ---------- 顶部标题行 ----------
        HBox topBar = new HBox(10);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setPadding(new Insets(14, 16, 10, 16));
        Button back = new Button("←");
        back.setStyle("-fx-background-color: transparent; -fx-font-size: 18px;"
                + "-fx-text-fill: " + Theme.TEXT_MUTED + "; -fx-cursor: hand;"
                + "-fx-padding: 0 8 0 0;");
        back.setOnAction(e -> window.closeOverlay());
        Label title = new Label("实例选择");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: "
                + Theme.TEXT + ";");
        topBar.getChildren().addAll(back, title);
        overlay.setTop(topBar);

        // ---------- 左侧：文件夹列表 ----------
        VBox leftBox = new VBox(6);
        leftBox.setPadding(new Insets(0, 12, 12, 16));
        leftBox.setPrefWidth(230);
        leftBox.setMinWidth(230);
        leftBox.setMaxWidth(230);

        Label folderTitle = new Label("文件夹列表");
        folderTitle.setStyle("-fx-font-size: 12px; -fx-text-fill: " + Theme.TEXT_MUTED + ";");
        leftBox.getChildren().add(folderTitle);

        // 当前目录项（高亮）
        VBox currentItem = new VBox(2);
        currentItem.setPadding(new Insets(8, 10, 8, 10));
        currentItem.setStyle("-fx-background-color: #e3eefb; -fx-background-radius: 6;"
                + "-fx-border-color: " + Theme.PRIMARY + "; -fx-border-radius: 6;");
        Label curLabel = new Label("当前文件夹");
        curLabel.setStyle("-fx-font-size: 12.5px; -fx-font-weight: bold; -fx-text-fill: "
                + Theme.PRIMARY + ";");
        Label curPath = new Label(".minecraft");
        curPath.setStyle("-fx-font-size: 11px; -fx-text-fill: " + Theme.TEXT_MUTED + ";");
        Label curPath2 = new Label(Constants.MC_DIR.toString());
        curPath2.setStyle("-fx-font-size: 10.5px; -fx-text-fill: " + Theme.TEXT_MUTED + ";");
        curPath2.setWrapText(true);
        currentItem.getChildren().addAll(curLabel, curPath, curPath2);
        leftBox.getChildren().add(currentItem);

        // 添加或导入
        Label addTitle = new Label("添加或导入");
        addTitle.setStyle("-fx-font-size: 12px; -fx-text-fill: " + Theme.TEXT_MUTED
                + "; -fx-padding: 8 0 0 0;");
        leftBox.getChildren().add(addTitle);

        Button addFolder = new Button("📁  添加已有文件夹");
        addFolder.setMaxWidth(Double.MAX_VALUE);
        addFolder.setAlignment(Pos.CENTER_LEFT);
        addFolder.setStyle("-fx-background-color: white; -fx-background-radius: 6;"
                + "-fx-border-color: " + Theme.BORDER_LIGHT + "; -fx-border-radius: 6;"
                + "-fx-padding: 8 10 8 10; -fx-font-size: 12.5px; -fx-cursor: hand;");
        addFolder.setOnAction(e -> addExistingInstanceFolder());

        Button importPack = new Button("📦  导入整合包");
        importPack.setMaxWidth(Double.MAX_VALUE);
        importPack.setAlignment(Pos.CENTER_LEFT);
        importPack.setStyle("-fx-background-color: white; -fx-background-radius: 6;"
                + "-fx-border-color: " + Theme.BORDER_LIGHT + "; -fx-border-radius: 6;"
                + "-fx-padding: 8 10 8 10; -fx-font-size: 12.5px; -fx-cursor: hand;");
        importPack.setOnAction(e -> {
            window.closeOverlay();
            window.switchTo("下载");
            // 切到整合包分类
            window.setStatus("已跳转到整合包页面，可拖入文件或点导入");
        });

        leftBox.getChildren().addAll(addFolder, importPack);
        overlay.setLeft(leftBox);

        // ---------- 右侧：实例列表 ----------
        VBox rightBox = new VBox(8);
        rightBox.setPadding(new Insets(0, 16, 12, 0));

        TextField search = new TextField();
        search.setPromptText("🔍  搜索游戏实例");
        search.setStyle("-fx-font-size: 12.5px; -fx-padding: 8 12 8 12;");

        ScrollPane scroll = new ScrollPane();
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        VBox listBox = new VBox(4);
        listBox.setPadding(new Insets(4));
        scroll.setContent(listBox);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        rightBox.getChildren().addAll(search, scroll);
        overlay.setCenter(rightBox);

        // 异步加载实例
        CompletableFuture.runAsync(() -> {
            List<String> versions = versionManager.installedVersions();
            Platform.runLater(() -> {
                if (versions.isEmpty()) {
                    Label empty = new Label("还没有安装任何版本，请先到「下载」页面安装");
                    empty.setStyle("-fx-text-fill: " + Theme.TEXT_MUTED + ";");
                    listBox.getChildren().add(empty);
                    return;
                }
                renderInstanceList(listBox, versions, "");
                // 搜索过滤
                search.textProperty().addListener((obs, was, now) ->
                        renderInstanceList(listBox, versions,
                                now == null ? "" : now.trim().toLowerCase()));
            });
        });

        window.showOverlay(overlay);
    }

    /** 渲染实例列表（带搜索过滤） */
    private void renderInstanceList(VBox listBox, List<String> versions, String keyword) {
        listBox.getChildren().clear();
        InstanceManager im = new InstanceManager(Constants.MC_DIR);
        String current = config.getString("current_version");

        // 分组：普通版本 / 带加载器或整合包
        List<String[]> rows = new ArrayList<>();
        for (String v : versions) {
            String display = im.displayName(v);
            String base = im.resolveBaseVersion(v);
            if (!keyword.isEmpty()
                    && !v.toLowerCase().contains(keyword)
                    && !display.toLowerCase().contains(keyword)) {
                continue;
            }
            rows.add(new String[]{v, display, base});
        }

        if (rows.isEmpty()) {
            Label none = new Label("没有匹配的实例");
            none.setStyle("-fx-text-fill: " + Theme.TEXT_MUTED + ";");
            listBox.getChildren().add(none);
            return;
        }

        Label header = new Label("可安装模组 (" + rows.size() + ")");
        header.setStyle("-fx-font-size: 12.5px; -fx-font-weight: bold; -fx-text-fill: "
                + Theme.PRIMARY + "; -fx-padding: 2 0 4 4;");
        listBox.getChildren().add(header);

        for (String[] row : rows) {
            String id = row[0];
            String display = row[1];
            String base = row[2];
            boolean selected = id.equals(current);

            HBox item = new HBox(10);
            item.setAlignment(Pos.CENTER_LEFT);
            item.setPadding(new Insets(9, 12, 9, 12));
            item.setMaxWidth(Double.MAX_VALUE);
            String baseStyle = "-fx-background-radius: 6; -fx-border-radius: 6;"
                    + "-fx-cursor: hand;";
            String normalStyle = "-fx-background-color: white; -fx-border-color: "
                    + Theme.BORDER_LIGHT + ";" + baseStyle;
            String selStyle = "-fx-background-color: #e9f2fb; -fx-border-color: "
                    + Theme.PRIMARY + ";" + baseStyle;
            item.setStyle(selected ? selStyle : normalStyle);

            // 左侧：名称 + 副标题
            VBox info = new VBox(2);
            HBox.setHgrow(info, Priority.ALWAYS);
            info.setMinWidth(0);
            Label nameLbl = new Label(display);
            nameLbl.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: "
                    + Theme.TEXT + ";");
            Label subLbl = new Label(base + (id.equals(display) ? "" : "  ·  " + id));
            subLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: " + Theme.TEXT_MUTED + ";");
            info.getChildren().addAll(nameLbl, subLbl);

            // 右侧：操作按钮
            HBox actions = new HBox(4);
            actions.setAlignment(Pos.CENTER_RIGHT);

            Button fav = iconButton("♡", "收藏");
            Button open = iconButton("📂", "打开目录");
            open.setOnAction(e -> {
                try {
                    java.awt.Desktop.getDesktop().open(
                            Constants.MC_DIR.resolve("versions").resolve(id).toFile());
                } catch (Exception ex) {
                    Log.warn("打开目录失败: " + ex.getMessage());
                }
            });
            Button del = iconButton("🗑", "删除实例");
            del.setOnAction(e -> deleteInstance(id, display));
            Button cfg = iconButton("⚙", "实例设置");
            cfg.setOnAction(e -> {
                window.closeOverlay();
                window.switchTo("设置");
            });
            actions.getChildren().addAll(fav, open, del, cfg);

            item.getChildren().addAll(info, actions);

            // 点击整行切换实例
            item.setOnMouseClicked(e -> {
                config.set("current_version", id).save();
                refreshVersionLabel();
                Log.info("已选择实例: " + id + " (" + display + ")");
                renderInstanceList(listBox, versions, keyword);
            });

            listBox.getChildren().add(item);
        }
    }

    /** 小图标按钮 */
    private Button iconButton(String icon, String tooltip) {
        Button b = new Button(icon);
        b.setStyle("-fx-background-color: transparent; -fx-font-size: 13px;"
                + "-fx-text-fill: " + Theme.TEXT_MUTED + "; -fx-padding: 4 8 4 8;"
                + "-fx-cursor: hand;");
        b.setTooltip(new Tooltip(tooltip));
        return b;
    }

    /** 添加已有实例文件夹 */
    private void addExistingInstanceFolder() {
        javafx.stage.DirectoryChooser dc = new javafx.stage.DirectoryChooser();
        dc.setTitle("选择一个包含 minecraft 版本 JSON 的文件夹");
        java.io.File dir = dc.showDialog(window.stage());
        if (dir == null) return;
        Log.info("添加实例文件夹: " + dir.getAbsolutePath());
        showInfo("已记录该文件夹：\n" + dir.getAbsolutePath()
                + "\n\n提示：把该文件夹下的版本复制到 .minecraft/versions/ 即可被识别。");
    }

    /** 删除实例 */
    private void deleteInstance(String id, String display) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("删除实例");
        confirm.setHeaderText("确定要删除该实例吗？");
        confirm.setContentText("实例: " + display + "\nID: " + id
                + "\n\n这会永久删除该实例的所有文件，不可恢复。");
        confirm.initOwner(window.stage());
        confirm.showAndWait().ifPresent(bt -> {
            if (bt != javafx.scene.control.ButtonType.OK
                    && bt != javafx.scene.control.ButtonType.YES) return;
            CompletableFuture.runAsync(() -> {
                try {
                    java.nio.file.Path dir = Constants.MC_DIR.resolve("versions").resolve(id);
                    if (Files.isDirectory(dir)) {
                        try (var s = Files.walk(dir)) {
                            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                            });
                        }
                    }
                    Platform.runLater(() -> {
                        Log.info("已删除实例: " + id);
                        showInstancePicker();
                    });
                } catch (Exception e) {
                    Log.error("删除实例失败", e);
                    Platform.runLater(() -> showError("删除失败", e.getMessage()));
                }
            });
        });
    }

    private void showInfo(String msg) {
        Alert a = new Alert(Alert.AlertType.INFORMATION);
        a.setTitle("提示");
        a.setHeaderText(null);
        a.setContentText(msg);
        a.initOwner(window.stage());
        a.showAndWait();
    }

    private static int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private void showError(String title, String msg) {
        Platform.runLater(() -> {
            Alert a = new Alert(Alert.AlertType.ERROR);
            a.setTitle(title);
            a.setHeaderText(null);
            a.setContentText(msg);
            a.initOwner(window.stage());
            a.showAndWait();
        });
    }
}
