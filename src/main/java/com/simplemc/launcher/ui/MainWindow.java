package com.simplemc.launcher.ui;

import com.simplemc.launcher.util.Constants;
import com.simplemc.launcher.util.Log;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.io.InputStream;

/**
 * 主窗口：自绘标题栏 + 内容区。
 */
public final class MainWindow {

    private final Stage stage;
    private final BorderPane root = new BorderPane();
    private final StackPane contentHost = new StackPane();
    private final HBox navBox = new HBox(2);
    private final Label statusLabel = new Label("");

    private javafx.scene.control.ProgressBar globalProgress;
    private Label progressText;
    private Label progressPercent;

    private final java.util.Map<String, Runnable> pages = new java.util.LinkedHashMap<>();
    private final java.util.Map<String, Button> navButtons = new java.util.LinkedHashMap<>();
    private String currentPage = "";
    private double dragOffsetX, dragOffsetY;

    public MainWindow(Stage stage) {
        this.stage = stage;
    }

    public void show() {
        root.setStyle("-fx-background-color: " + Theme.BG + ";");

        BorderPane shell = new BorderPane();
        shell.setTop(buildTitleBar());
        shell.setCenter(contentHost);
        shell.setBottom(buildProgressBar());
        root.setCenter(shell);

        Scene scene = new Scene(root, 1080, 760);
        scene.getStylesheets().add("data:text/css;base64," + java.util.Base64.getEncoder()
                .encodeToString(Theme.CSS.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        // 全窗口拖拽导入整合包
        setupGlobalDragDrop(scene);

        stage.initStyle(StageStyle.UNDECORATED);
        stage.setScene(scene);
        stage.setTitle(Constants.APP_TITLE);
        stage.setMinWidth(960);
        stage.setMinHeight(660);

        // 图标
        try (InputStream in = MainWindow.class.getResourceAsStream("/icon.png")) {
            if (in != null) {
                stage.getIcons().add(new Image(in));
            }
        } catch (Exception ignored) {}

        stage.show();
        Log.info("主窗口已显示");
    }

    // ---------------- 全局拖拽导入整合包 ----------------

    /** 拖放回调：由 DownloadPage 注册 */
    private java.util.function.Consumer<java.nio.file.Path> modpackDropHandler;

    /** 拖拽时的全屏蒙层 */
    private javafx.scene.Node dropOverlay;

    public void setModpackDropHandler(java.util.function.Consumer<java.nio.file.Path> handler) {
        this.modpackDropHandler = handler;
    }

    /**
     * 全窗口拖拽：
     * 把 .zip / .mrpack 拖到启动器任意位置都能导入整合包。
     * 拖动时显示蒙层提示，松手后自动交给 DownloadPage 处理。
     */
    private void setupGlobalDragDrop(Scene scene) {
        scene.setOnDragEntered(e -> {
            if (hasModpackFile(e.getDragboard())) {
                showDropOverlay();
            }
        });
        scene.setOnDragOver(e -> {
            if (hasModpackFile(e.getDragboard())) {
                e.acceptTransferModes(javafx.scene.input.TransferMode.COPY);
            }
            e.consume();
        });
        scene.setOnDragExited(e -> hideDropOverlay());
        scene.setOnDragDropped(e -> {
            hideDropOverlay();
            var db = e.getDragboard();
            boolean ok = false;
            if (db.hasFiles() && !db.getFiles().isEmpty()) {
                java.io.File f = db.getFiles().get(0);
                String name = f.getName().toLowerCase();
                if (name.endsWith(".mrpack") || name.endsWith(".zip")) {
                    if (modpackDropHandler != null) {
                        modpackDropHandler.accept(f.toPath());
                        ok = true;
                    }
                }
            }
            e.setDropCompleted(ok);
            e.consume();
        });
    }

    private boolean hasModpackFile(javafx.scene.input.Dragboard db) {
        if (db == null || !db.hasFiles() || db.getFiles().isEmpty()) return false;
        String name = db.getFiles().get(0).getName().toLowerCase();
        return name.endsWith(".mrpack") || name.endsWith(".zip");
    }

    /** 显示拖拽蒙层（铺满整个窗口） */
    private void showDropOverlay() {
        if (dropOverlay != null) return;

        VBox box = new VBox(14);
        box.setAlignment(Pos.CENTER);
        box.setStyle("-fx-background-color: rgba(53,114,176,0.92);");
        box.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        Label icon = new Label("📦");
        icon.setStyle("-fx-font-size: 64px;");

        Label title = new Label("松开即可导入整合包");
        title.setStyle("-fx-font-size: 24px; -fx-font-weight: bold; -fx-text-fill: white;");

        Label sub = new Label("支持 .mrpack 和 .zip 格式");
        sub.setStyle("-fx-font-size: 14px; -fx-text-fill: #dce8f5;");

        box.getChildren().addAll(icon, title, sub);

        // 蒙层不拦截鼠标事件，避免影响拖拽
        box.setMouseTransparent(true);
        box.setPickOnBounds(false);

        dropOverlay = box;
        root.getChildren().add(box);
        box.toFront();
    }

    private void hideDropOverlay() {
        if (dropOverlay != null) {
            root.getChildren().remove(dropOverlay);
            dropOverlay = null;
        }
    }

    // ---------------- 标题栏 ----------------

    private HBox buildTitleBar() {
        HBox bar = new HBox(10);
        bar.getStyleClass().add("titlebar");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(0, 0, 0, 12));

        // 图标
        try (InputStream in = MainWindow.class.getResourceAsStream("/icon.png")) {
            if (in != null) {
                ImageView iv = new ImageView(new Image(in, 24, 24, true, true));
                bar.getChildren().add(iv);
            }
        } catch (Exception ignored) {}

        Label title = new Label("Simple MC");
        title.getStyleClass().add("titlebar-label");
        bar.getChildren().add(title);

        // 导航
        navBox.setAlignment(Pos.CENTER_LEFT);
        navBox.setPadding(new Insets(0, 0, 0, 20));
        bar.getChildren().add(navBox);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        bar.getChildren().add(spacer);

        // 状态文字（很淡）
        statusLabel.setStyle("-fx-text-fill: #dce8f5; -fx-font-size: 12px;");
        statusLabel.setPadding(new Insets(0, 12, 0, 0));
        bar.getChildren().add(statusLabel);

        // 窗口按钮
        Button min = new Button("—");
        min.getStyleClass().addAll("win-btn");
        min.setOnAction(e -> stage.setIconified(true));

        Button max = new Button("□");
        max.getStyleClass().addAll("win-btn");
        max.setOnAction(e -> stage.setMaximized(!stage.isMaximized()));

        Button close = new Button("×");
        close.getStyleClass().addAll("win-btn", "win-btn-close");
        close.setOnAction(e -> {
            Log.info("用户关闭窗口");
            Platform.exit();
            System.exit(0);
        });

        bar.getChildren().addAll(min, max, close);

        // 拖动窗口
        bar.setOnMousePressed(e -> {
            dragOffsetX = e.getScreenX() - stage.getX();
            dragOffsetY = e.getScreenY() - stage.getY();
        });
        bar.setOnMouseDragged(e -> {
            if (!stage.isMaximized()) {
                stage.setX(e.getScreenX() - dragOffsetX);
                stage.setY(e.getScreenY() - dragOffsetY);
            }
        });
        bar.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                stage.setMaximized(!stage.isMaximized());
            }
        });

        return bar;
    }

    /** 注册一个页面 */
    public void addPage(String name, Runnable onShow) {
        pages.put(name, onShow);
        Button btn = new Button(name);
        btn.getStyleClass().add("nav-btn");
        btn.setOnAction(e -> switchTo(name));
        navBox.getChildren().add(btn);
        navButtons.put(name, btn);
    }

    /** 切换页面 */
    public void switchTo(String name) {
        Runnable show = pages.get(name);
        if (show == null) return;
        // 切换页面时先关掉可能存在的覆盖层
        closeOverlay();
        currentPage = name;
        navButtons.forEach((k, b) -> {
            b.getStyleClass().remove("nav-btn-active");
            if (k.equals(name)) b.getStyleClass().add("nav-btn-active");
        });
        try {
            show.run();
        } catch (Exception e) {
            Log.error("切换页面失败: " + name, e);
        }
    }

    public String currentPage() {
        return currentPage;
    }

    /** 内容区域（供页面放置） */
    public StackPane contentHost() {
        return contentHost;
    }

    // ---------------- 内嵌覆盖层 ----------------

    private javafx.scene.Node overlayNode;

    /**
     * 在启动器内容区上方显示一个覆盖层（铺满整个内容区域，不弹独立窗口）。
     * 同时会隐藏左侧导航按钮的点击效果，避免误切换。
     */
    public void showOverlay(javafx.scene.Node overlay) {
        closeOverlay();
        // 包一层透明底，保证覆盖层铺满且能捕获点击
        StackPane wrapper = new StackPane(overlay);
        wrapper.setStyle("-fx-background-color: " + Theme.BG + ";");
        wrapper.setPickOnBounds(true);
        overlayNode = wrapper;
        contentHost.getChildren().add(wrapper);
        // 保证在最上层
        wrapper.toFront();
    }

    /** 关闭当前覆盖层 */
    public void closeOverlay() {
        if (overlayNode != null) {
            contentHost.getChildren().remove(overlayNode);
            overlayNode = null;
        }
    }

    public boolean hasOverlay() {
        return overlayNode != null;
    }

    public Stage stage() {
        return stage;
    }

    // ---------------- 底部进度 ----------------

    private HBox buildProgressBar() {
        HBox bar = new HBox(12);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8, 16, 8, 16));
        bar.setStyle("-fx-background-color: white; -fx-border-color: "
                + Theme.BORDER_LIGHT + " transparent transparent transparent;"
                + "-fx-border-width: 1 0 0 0;");

        progressText = new Label("就绪");
        progressText.getStyleClass().add("muted");
        progressText.setPrefWidth(460);

        globalProgress = new javafx.scene.control.ProgressBar(0);
        globalProgress.setPrefHeight(12);
        HBox.setHgrow(globalProgress, Priority.ALWAYS);

        progressPercent = new Label("0%");
        progressPercent.setStyle("-fx-font-weight: bold; -fx-text-fill: " + Theme.PRIMARY + ";");
        progressPercent.setPrefWidth(46);
        progressPercent.setAlignment(Pos.CENTER_RIGHT);

        bar.getChildren().addAll(progressText, globalProgress, progressPercent);
        return bar;
    }

    /** 更新全局进度 */
    public void setProgress(String text, double fraction, String percentText) {
        Platform.runLater(() -> {
            if (progressText != null) progressText.setText(text == null ? "" : text);
            if (globalProgress != null) globalProgress.setProgress(
                    Math.max(0, Math.min(1, fraction)));
            if (progressPercent != null) {
                progressPercent.setText(percentText != null ? percentText
                        : String.format("%.0f%%", fraction * 100));
            }
        });
    }

    /** 顶部状态文字 */
    public void setStatus(String text) {
        Platform.runLater(() -> {
            if (statusLabel != null) statusLabel.setText(text == null ? "" : text);
        });
    }
}
