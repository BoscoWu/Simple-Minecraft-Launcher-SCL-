package com.simplemc.launcher.ui.page;

import com.simplemc.launcher.core.*;
import com.simplemc.launcher.ui.MainWindow;
import com.simplemc.launcher.ui.Theme;
import com.simplemc.launcher.util.Config;
import com.simplemc.launcher.util.Constants;
import com.simplemc.launcher.util.Downloader;
import com.simplemc.launcher.util.Log;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 下载页：左侧分类（游戏/模组/整合包/...），右侧内容。
 */
public final class DownloadPage {

    private static final String[] CATEGORIES = {
            "游戏", "模组", "整合包", "数据包", "资源包", "光影包", "世界", "收藏夹"
    };
    private static final Map<String, String> CATEGORY_ICON = new LinkedHashMap<>();
    static {
        CATEGORY_ICON.put("游戏", "🎮");
        CATEGORY_ICON.put("模组", "🔧");
        CATEGORY_ICON.put("整合包", "📦");
        CATEGORY_ICON.put("数据包", "🗂");
        CATEGORY_ICON.put("资源包", "🎨");
        CATEGORY_ICON.put("光影包", "✨");
        CATEGORY_ICON.put("世界", "🌍");
        CATEGORY_ICON.put("收藏夹", "⭐");
    }

    private final MainWindow window;
    private final Config config;
    private final VersionManager versionManager = new VersionManager(null);
    private final VanillaInstaller vanillaInstaller = new VanillaInstaller(Constants.MC_DIR);
    private final LoaderInstaller loaderInstaller = new LoaderInstaller(Constants.MC_DIR);

    private final BorderPane root = new BorderPane();
    private final VBox navBox = new VBox(2);
    private final StackPane contentArea = new StackPane();
    private final Map<String, Button> navButtons = new LinkedHashMap<>();
    private final Map<String, VBox> pages = new LinkedHashMap<>();

    private String currentCategory = "游戏";

    // 游戏页控件
    private VBox versionListBox;

    public DownloadPage(MainWindow window, Config config) {
        this.window = window;
        this.config = config;
        build();
    }

    public BorderPane root() {
        return root;
    }

    private void build() {
        // 左侧分类
        navBox.getStyleClass().add("dl-nav");
        navBox.setPadding(new Insets(12, 8, 12, 8));
        // 分类按钮之间统一间距，保证视觉上完全一致
        navBox.setSpacing(2);
        navBox.setFillWidth(true);

        Label header = new Label("下载中心");
        header.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: "
                + Theme.PRIMARY + "; -fx-padding: 0 0 10 8;");
        navBox.getChildren().add(header);

        for (String cat : CATEGORIES) {
            // 不显示 emoji 图标，所有分类标签保持同样大小
            Button b = new Button(cat);
            b.getStyleClass().add("dl-nav-item");
            b.setMaxWidth(Double.MAX_VALUE);
            b.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            b.setPrefHeight(36);
            b.setMinHeight(36);
            b.setMaxHeight(36);
            b.setOnAction(e -> showCategory(cat));
            navBox.getChildren().add(b);
            navButtons.put(cat, b);
        }
        root.setLeft(navBox);

        contentArea.setStyle("-fx-background-color: " + Theme.BG + ";");
        root.setCenter(contentArea);

        // 预建页面
        pages.put("游戏", buildGamePage());
        for (String cat : CATEGORIES) {
            if (!pages.containsKey(cat)) {
                pages.put(cat, buildModrinthPage(cat));
            }
        }

        showCategory("游戏");
    }

    /** 切换分类 */
    public void showCategory(String cat) {
        currentCategory = cat;
        navButtons.forEach((k, b) -> {
            b.getStyleClass().remove("dl-nav-item-active");
            if (k.equals(cat)) b.getStyleClass().add("dl-nav-item-active");
        });
        contentArea.getChildren().setAll(pages.get(cat));
    }

    // ---------------- 游戏页 ----------------

    private VBox buildGamePage() {
        VBox page = new VBox(10);
        page.setPadding(new Insets(16));

        // 版本列表（加载器选择移到了点击版本后的下载对话框里）
        ScrollPane scroll = new ScrollPane();
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        versionListBox = new VBox(8);
        versionListBox.setPadding(new Insets(0, 2, 8, 2));
        scroll.setContent(versionListBox);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        page.getChildren().add(scroll);

        // 异步加载版本
        CompletableFuture.runAsync(() -> {
            try {
                List<VersionManager.VersionInfo> all = versionManager.listAllVersions();
                Platform.runLater(() -> {
                    renderVersionGroups(all);
                    window.setProgress("就绪", 0, "0%");
                });
            } catch (Exception e) {
                Log.error("加载版本列表失败", e);
                Platform.runLater(() -> {
                    Label err = new Label("加载版本列表失败: " + e.getMessage());
                    err.getStyleClass().add("hint");
                    versionListBox.getChildren().setAll(err);
                });
            }
        });

        return page;
    }

    /** 按 4 组渲染版本列表（正式版/预览版/远古版/愚人节版） */
    private void renderVersionGroups(List<VersionManager.VersionInfo> all) {
        versionListBox.getChildren().clear();

        Map<String, List<VersionManager.VersionInfo>> groups = new LinkedHashMap<>();
        groups.put("正式版", new ArrayList<>());
        groups.put("预览版", new ArrayList<>());
        groups.put("远古版", new ArrayList<>());
        groups.put("愚人节版", new ArrayList<>());

        for (VersionManager.VersionInfo v : all) {
            String type = v.type();
            if ("release".equals(type)) {
                groups.get("正式版").add(v);
            } else if ("snapshot".equals(type)) {
                // 4 月 1 日发布的一律归为愚人节版
                if (isAprilFools(v.id(), v.releaseTime())) {
                    groups.get("愚人节版").add(v);
                } else {
                    groups.get("预览版").add(v);
                }
            } else {
                groups.get("远古版").add(v);
            }
        }

        // ---------- 最新版本卡片 ----------
        versionListBox.getChildren().add(buildLatestCard(all, groups));

        // ---------- 四个可折叠分组 ----------
        for (var e : groups.entrySet()) {
            List<VersionManager.VersionInfo> list = e.getValue();
            if (list.isEmpty()) continue;
            versionListBox.getChildren().add(buildGroupSection(e.getKey(), list));
        }
    }

    /** 顶部"最新版本"卡片：最新正式版 + 最新预览版（不带图标） */
    private VBox buildLatestCard(List<VersionManager.VersionInfo> all,
                                 Map<String, List<VersionManager.VersionInfo>> groups) {
        VBox card = new VBox(6);
        card.setPadding(new Insets(14));
        card.setStyle("-fx-background-color: white; -fx-background-radius: 8;"
                + "-fx-border-color: " + Theme.BORDER_LIGHT + "; -fx-border-radius: 8;");

        Label title = new Label("最新版本");
        title.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: "
                + Theme.PRIMARY + ";");
        card.getChildren().add(title);

        List<VersionManager.VersionInfo> releases = groups.get("正式版");
        if (!releases.isEmpty()) {
            card.getChildren().add(makeLatestRow(releases.get(0), "最新正式版"));
        }
        List<VersionManager.VersionInfo> snapshots = groups.get("预览版");
        if (!snapshots.isEmpty()) {
            card.getChildren().add(makeLatestRow(snapshots.get(0), "最新预览版"));
        }
        return card;
    }

    /** 最新版本的一行：版本号 + 说明 + 安装按钮（无图标） */
    private HBox makeLatestRow(VersionManager.VersionInfo v, String label) {
        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(6, 0, 6, 0));

        VBox info = new VBox(2);
        HBox.setHgrow(info, Priority.ALWAYS);
        info.setMinWidth(0);
        Label name = new Label(v.id());
        name.setStyle("-fx-font-size: 13.5px; -fx-font-weight: bold; -fx-text-fill: "
                + Theme.TEXT + ";");
        String time = formatReleaseTime(v.releaseTime());
        Label meta = new Label(label + "，发布于 " + time);
        meta.setStyle("-fx-font-size: 11.5px; -fx-text-fill: " + Theme.TEXT_MUTED + ";");
        info.getChildren().addAll(name, meta);

        Button install = new Button("安装");
        install.getStyleClass().add("btn-primary");
        install.setStyle("-fx-padding: 3 14 3 14; -fx-font-size: 12px;");
        install.setMinWidth(64);
        install.setPrefWidth(64);
        install.setOnAction(e -> showInstallDialog(v));

        row.getChildren().addAll(info, install);
        return row;
    }

    /** 把 ISO 时间转成 2026/9/15 19:23 这种形式 */
    private static String formatReleaseTime(String iso) {
        if (iso == null || iso.isBlank()) return "未知时间";
        try {
            // 形如 2026-09-15T19:23:00+00:00
            java.time.OffsetDateTime odt = java.time.OffsetDateTime.parse(iso);
            java.time.ZonedDateTime local =
                    odt.atZoneSameInstant(java.time.ZoneId.systemDefault());
            return String.format("%d/%d/%d %02d:%02d",
                    local.getYear(), local.getMonthValue(), local.getDayOfMonth(),
                    local.getHour(), local.getMinute());
        } catch (Exception e) {
            try {
                java.time.Instant inst = java.time.Instant.parse(iso);
                java.time.ZonedDateTime local =
                        inst.atZone(java.time.ZoneId.systemDefault());
                return String.format("%d/%d/%d %02d:%02d",
                        local.getYear(), local.getMonthValue(), local.getDayOfMonth(),
                        local.getHour(), local.getMinute());
            } catch (Exception e2) {
                return iso.length() > 10 ? iso.substring(0, 10) : iso;
            }
        }
    }

    /**
     * 一个可折叠的版本分组：标题行（名称 + 数量 + 箭头）+ 展开后的版本列表。
     * 样式沿用本项目的蓝色主题。
     */
    private VBox buildGroupSection(String name, List<VersionManager.VersionInfo> list) {
        VBox section = new VBox(0);
        section.setStyle("-fx-background-color: white; -fx-background-radius: 8;"
                + "-fx-border-color: " + Theme.BORDER_LIGHT + "; -fx-border-radius: 8;");

        // ---- 标题行 ----
        HBox header = new HBox(8);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(12, 14, 12, 14));
        header.setStyle("-fx-cursor: hand;");

        Label title = new Label(name + " (" + list.size() + ")");
        title.setStyle("-fx-font-size: 13.5px; -fx-font-weight: bold; -fx-text-fill: "
                + Theme.TEXT + ";");
        HBox.setHgrow(title, Priority.ALWAYS);
        title.setMaxWidth(Double.MAX_VALUE);

        Label arrow = new Label("∨");
        arrow.setStyle("-fx-font-size: 12px; -fx-text-fill: " + Theme.TEXT_MUTED + ";");

        header.getChildren().addAll(title, arrow);

        // ---- 内容区 ----
        VBox content = new VBox(3);
        content.setPadding(new Insets(0, 10, 10, 10));
        content.setVisible(false);
        content.setManaged(false);

        // 懒加载：首次展开时才填充
        final boolean[] loaded = {false};
        final int PAGE = 40;

        Runnable loadFirst = () -> {
            if (loaded[0]) return;
            loaded[0] = true;
            int limit = Math.min(list.size(), PAGE);
            for (int i = 0; i < limit; i++) {
                content.getChildren().add(makeVersionRow(list.get(i)));
            }
            if (list.size() > limit) {
                Button more = new Button("加载更多（还有 " + (list.size() - limit) + " 个）");
                more.getStyleClass().add("btn-gray");
                more.setOnAction(ev -> {
                    int from = content.getChildren().size() - 1;
                    int to = Math.min(list.size(), from + PAGE);
                    content.getChildren().remove(more);
                    for (int i = from; i < to; i++) {
                        content.getChildren().add(makeVersionRow(list.get(i)));
                    }
                    if (to < list.size()) content.getChildren().add(more);
                });
                content.getChildren().add(more);
            }
        };

        // 点击标题展开/收起
        header.setOnMouseClicked(e -> {
            boolean show = !content.isVisible();
            if (show) {
                loadFirst.run();
            }
            content.setVisible(show);
            content.setManaged(show);
            arrow.setText(show ? "∧" : "∨");
        });

        // 初始全部收起（进入下载页时所有下拉框都是收起状态）
        content.setVisible(false);
        content.setManaged(false);
        arrow.setText("∨");

        section.getChildren().addAll(header, content);
        return section;
    }

    /** 已知的愚人节版本 ID（发布日期为 4 月 1 日，作为辅助判据） */
    private static final java.util.Set<String> APRIL_FOOLS = java.util.Set.of(
            "15w14a",
            "1.rv-pre1",
            "3d shareware v1.34",
            "20w14infinite",
            "22w13oneblockatatime",
            "23w13a_or_b",
            "24w14potato",
            "25w14craftmine");

    /**
     * 判断是否为愚人节版本。
     *
     * <p><b>主要依据：发布时间为 4 月 1 日</b>（Mojang 的愚人节彩蛋版本都在这一天发布）。</p>
     *
     * <p>辅助依据（防止清单里时间戳缺失或时区差异导致漏判）：
     * 编号匹配已知版本 ID，或 ID 中含明显彩蛋关键词。</p>
     *
     * @param id          版本 ID
     * @param releaseTime ISO 格式发布时间，如 2024-04-01T12:00:00+00:00
     */
    private boolean isAprilFools(String id, String releaseTime) {
        // 1. 优先看发布日期是否为 4 月 1 日
        if (isAprilFirst(releaseTime)) return true;

        // 2. 兜底：已知愚人节版本 ID
        if (id == null) return false;
        String low = id.toLowerCase().trim();
        if (APRIL_FOOLS.contains(low)) return true;

        // 3. 再兜底：ID 里带明显彩蛋关键词
        if (low.contains("shareware") || low.contains("oneblockatatime")
                || low.contains("craftmine") || low.endsWith("potato")
                || low.contains("_or_b") || low.contains("rv-pre")) {
            return true;
        }
        // 含非 ASCII 字符（某些清单会写成 20w14∞）
        for (int i = 0; i < id.length(); i++) {
            if (id.charAt(i) > 127) return true;
        }
        return false;
    }

    /** 发布时间是否为 4 月 1 日（按本地时区判断，兼容多种 ISO 写法） */
    private static boolean isAprilFirst(String releaseTime) {
        if (releaseTime == null || releaseTime.isBlank()) return false;
        try {
            java.time.OffsetDateTime odt =
                    java.time.OffsetDateTime.parse(releaseTime);
            java.time.ZonedDateTime local =
                    odt.atZoneSameInstant(java.time.ZoneId.systemDefault());
            return local.getMonthValue() == 4 && local.getDayOfMonth() == 1;
        } catch (Exception ignored) {
        }
        try {
            java.time.Instant inst = java.time.Instant.parse(releaseTime);
            java.time.ZonedDateTime local =
                    inst.atZone(java.time.ZoneId.systemDefault());
            return local.getMonthValue() == 4 && local.getDayOfMonth() == 1;
        } catch (Exception ignored) {
        }
        // 最后按字符串兜底：匹配 -04-01 或 /04/01
        return releaseTime.contains("-04-01") || releaseTime.contains("/04/01");
    }

    private HBox makeVersionRow(VersionManager.VersionInfo v) {
        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(7, 10, 7, 10));
        row.setStyle("-fx-background-color: #f7fafd; -fx-background-radius: 5;"
                + "-fx-cursor: hand;");

        // 悬停高亮
        row.setOnMouseEntered(e -> row.setStyle(
                "-fx-background-color: #e9f2fb; -fx-background-radius: 5;"
                + "-fx-cursor: hand;"));
        row.setOnMouseExited(e -> row.setStyle(
                "-fx-background-color: #f7fafd; -fx-background-radius: 5;"
                + "-fx-cursor: hand;"));

        Label name = new Label(v.id());
        name.setStyle("-fx-font-weight: bold; -fx-font-size: 13px; -fx-text-fill: "
                + Theme.TEXT + ";");
        name.setPrefWidth(150);
        name.setMinWidth(150);       // 关键：防止被 spacer 挤成 0 宽
        name.setMaxWidth(Region.USE_PREF_SIZE);

        // 发布时间
        Label time = new Label(formatReleaseTime(v.releaseTime()));
        time.setStyle("-fx-font-size: 11.5px; -fx-text-fill: " + Theme.TEXT_MUTED + ";");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        spacer.setMinWidth(10);

        // 类型标签（浅色小徽章）
        Label type = new Label(v.typeName());
        type.setStyle("-fx-font-size: 10.5px; -fx-text-fill: " + Theme.PRIMARY + ";"
                + "-fx-background-color: #e3eefb; -fx-background-radius: 4;"
                + "-fx-padding: 1 7 1 7;");
        type.setMinWidth(Region.USE_PREF_SIZE);

        Button install = new Button("安装");
        install.getStyleClass().add("btn-primary");
        install.setStyle("-fx-padding: 3 14 3 14; -fx-font-size: 12px;");
        install.setMinWidth(64);
        install.setPrefWidth(64);
        install.setOnAction(e -> showInstallDialog(v));

        row.getChildren().addAll(name, time, spacer, type, install);
        row.setMinWidth(Region.USE_PREF_SIZE);
        return row;
    }

    // ---------------- 下载覆盖层（内嵌在主界面里，不是独立窗口）----------------

    /** 选中版本后覆盖整个启动器界面的下载面板：顶部版本名 + 加载器列表 + 开始下载 */
    private void showInstallDialog(VersionManager.VersionInfo v) {
        if (installing) {
            alert(Alert.AlertType.INFORMATION, "已有安装任务正在进行，请稍候");
            return;
        }

        // 覆盖层：用 BorderPane 铺满整个启动器内容区
        javafx.scene.layout.BorderPane overlay = new javafx.scene.layout.BorderPane();
        overlay.setStyle("-fx-background-color: " + Theme.BG + ";");
        overlay.setMaxWidth(Double.MAX_VALUE);
        overlay.setMaxHeight(Double.MAX_VALUE);

        // ---- 顶部区：返回箭头 + 版本号 ----
        VBox topArea = new VBox(8);
        topArea.setPadding(new Insets(14, 16, 10, 16));

        HBox top = new HBox(8);
        top.setAlignment(Pos.CENTER_LEFT);
        Button back = new Button("←");
        back.setStyle("-fx-background-color: transparent; -fx-font-size: 18px;"
                + "-fx-text-fill: " + Theme.TEXT_MUTED + "; -fx-cursor: hand;"
                + "-fx-padding: 0 8 0 0;");
        back.setOnAction(e -> window.closeOverlay());
        TextField verField = new TextField(v.id());
        verField.setEditable(false);
        verField.setDisable(true);
        verField.setStyle("-fx-font-size: 14px; -fx-opacity: 1;");
        HBox.setHgrow(verField, Priority.ALWAYS);
        verField.setMaxWidth(Double.MAX_VALUE);
        top.getChildren().addAll(back, verField);
        topArea.getChildren().add(top);

        // ---- 加载器列表 ----
        ToggleGroup group = new ToggleGroup();
        LoaderInstaller.Loader[] loaders = {
                LoaderInstaller.Loader.VANILLA,
                LoaderInstaller.Loader.FORGE,
                LoaderInstaller.Loader.NEOFORGE,
                LoaderInstaller.Loader.FABRIC,
                LoaderInstaller.Loader.QUILT,
                LoaderInstaller.Loader.OPTIFINE
        };
        VBox loaderBox = new VBox(6);
        loaderBox.setFillWidth(true);

        for (LoaderInstaller.Loader l : loaders) {
            HBox item = new HBox(10);
            item.setAlignment(Pos.CENTER_LEFT);
            item.setPadding(new Insets(10, 14, 10, 14));
            item.setMaxWidth(Double.MAX_VALUE);
            item.setStyle("-fx-background-color: white; -fx-background-radius: 8;"
                    + "-fx-border-color: " + Theme.BORDER_LIGHT + "; -fx-border-radius: 8;"
                    + "-fx-cursor: hand;");

            RadioButton rb = new RadioButton();
            rb.setToggleGroup(group);
            rb.setUserData(l);
            // 原版默认选中
            if (l == LoaderInstaller.Loader.VANILLA) rb.setSelected(true);

            Label name = new Label(l.display);
            name.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: "
                    + Theme.TEXT + ";");
            name.setPrefWidth(110);
            name.setMinWidth(110);

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            // 状态文字 / 可点击按钮取决于该加载器当前是否可用
            javafx.scene.Node actionNode = buildLoaderAction(l, v, rb);

            Label arrow = new Label("›");
            arrow.setStyle("-fx-font-size: 16px; -fx-text-fill: " + Theme.TEXT_MUTED + ";");

            item.getChildren().addAll(rb, name, spacer, actionNode, arrow);

            // 点击整行选中
            item.setOnMouseClicked(e -> rb.setSelected(true));
            // 选中时高亮
            rb.selectedProperty().addListener((obs, was, now) -> {
                if (now) {
                    item.setStyle("-fx-background-color: #e9f2fb; -fx-background-radius: 8;"
                            + "-fx-border-color: " + Theme.PRIMARY + "; -fx-border-radius: 8;"
                            + "-fx-border-width: 1.5; -fx-cursor: hand;");
                } else {
                    item.setStyle("-fx-background-color: white; -fx-background-radius: 8;"
                            + "-fx-border-color: " + Theme.BORDER_LIGHT + ";"
                            + "-fx-border-radius: 8; -fx-cursor: hand;");
                }
            });
            if (rb.isSelected()) {
                item.setStyle("-fx-background-color: #e9f2fb; -fx-background-radius: 8;"
                        + "-fx-border-color: " + Theme.PRIMARY + "; -fx-border-radius: 8;"
                        + "-fx-border-width: 1.5; -fx-cursor: hand;");
            }
            loaderBox.getChildren().add(item);
        }

        ScrollPane sp = new ScrollPane(loaderBox);
        sp.setFitToWidth(true);
        sp.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        sp.setPadding(new Insets(0, 16, 0, 16));

        // ---- 底部：开始下载（铺满整个窗口宽度）----
        Button startBtn = new Button("⬇  开始下载");
        startBtn.setMaxWidth(Double.MAX_VALUE);
        startBtn.setPrefHeight(46);
        startBtn.setStyle("-fx-background-color: " + Theme.PRIMARY + ";"
                + "-fx-text-fill: white; -fx-font-size: 15px; -fx-font-weight: bold;"
                + "-fx-background-radius: 8; -fx-cursor: hand;");
        HBox.setHgrow(startBtn, Priority.ALWAYS);
        startBtn.setOnAction(e -> {
            Toggle sel = group.getSelectedToggle();
            LoaderInstaller.Loader loader = sel == null
                    ? LoaderInstaller.Loader.VANILLA
                    : (LoaderInstaller.Loader) sel.getUserData();
            if (loader == LoaderInstaller.Loader.OPTIFINE) {
                alert(Alert.AlertType.INFORMATION,
                        "OptiFine 需要手动安装：\n请到 optifine.net 下载对应版本后双击安装");
                return;
            }
            window.closeOverlay();
            doInstallVersion(v.id(), loader);
        });

        VBox bottomArea = new VBox();
        bottomArea.setPadding(new Insets(10, 16, 16, 16));
        bottomArea.getChildren().add(startBtn);

        // 组装：顶部 / 中间可滚动 / 底部固定
        overlay.setTop(topArea);
        overlay.setCenter(sp);
        overlay.setBottom(bottomArea);

        Log.info("显示下载覆盖层: " + v.id());
        window.showOverlay(overlay);
    }

    /**
     * 加载器右侧的操作控件：
     * 原版 / OptiFine 显示说明文字；Forge/Fabric 等显示可点的「添加」按钮，
     * 点击会打开加载器子选项（目前提示可选版本）。
     */
    private javafx.scene.Node buildLoaderAction(LoaderInstaller.Loader l,
                                                VersionManager.VersionInfo v,
                                                RadioButton rb) {
        if (l == LoaderInstaller.Loader.VANILLA) {
            Label s = new Label("官方原版");
            s.setStyle("-fx-font-size: 12px; -fx-text-fill: " + Theme.TEXT_MUTED + ";");
            return s;
        }
        if (l == LoaderInstaller.Loader.OPTIFINE) {
            Label s = new Label("需手动安装");
            s.setStyle("-fx-font-size: 12px; -fx-text-fill: " + Theme.WARN + ";");
            return s;
        }

        // 其余加载器：可点击的「添加」按钮
        Button add = new Button("添加");
        add.setStyle("-fx-background-color: #e3eefb; -fx-text-fill: " + Theme.PRIMARY + ";"
                + "-fx-background-radius: 6; -fx-padding: 3 14 3 14;"
                + "-fx-font-size: 12px; -fx-font-weight: bold; -fx-cursor: hand;");
        add.setOnAction(e -> {
            rb.setSelected(true);
            showLoaderOptions(l, v);
        });
        return add;
    }

    /** 点击某个加载器的「添加」后，显示该加载器的可用版本列表 */
    private void showLoaderOptions(LoaderInstaller.Loader loader,
                                   VersionManager.VersionInfo v) {
        javafx.scene.layout.BorderPane overlay = new javafx.scene.layout.BorderPane();
        overlay.setStyle("-fx-background-color: " + Theme.BG + ";");

        VBox topArea = new VBox(8);
        topArea.setPadding(new Insets(14, 16, 10, 16));
        HBox top = new HBox(8);
        top.setAlignment(Pos.CENTER_LEFT);
        Button back = new Button("←");
        back.setStyle("-fx-background-color: transparent; -fx-font-size: 18px;"
                + "-fx-text-fill: " + Theme.TEXT_MUTED + "; -fx-cursor: hand;"
                + "-fx-padding: 0 8 0 0;");
        // 返回上一层（重新打开主选择面板）
        back.setOnAction(e -> showInstallDialog(v));
        Label title = new Label(loader.display + " — " + v.id());
        title.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: "
                + Theme.TEXT + ";");
        HBox.setHgrow(title, Priority.ALWAYS);
        title.setMaxWidth(Double.MAX_VALUE);
        top.getChildren().addAll(back, title);
        topArea.getChildren().add(top);
        overlay.setTop(topArea);

        VBox listBox = new VBox(6);
        listBox.setPadding(new Insets(0, 16, 0, 16));
        Label loading = new Label("正在获取可用版本...");
        loading.setStyle("-fx-text-fill: " + Theme.TEXT_MUTED + ";");
        listBox.getChildren().add(loading);

        ScrollPane sp = new ScrollPane(listBox);
        sp.setFitToWidth(true);
        sp.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        overlay.setCenter(sp);

        window.showOverlay(overlay);

        // 所有加载器都异步拉取真实版本列表（含 Forge / NeoForge）
        CompletableFuture.runAsync(() -> {
            List<LoaderInstaller.LoaderVersion> versions = new ArrayList<>();
            String err = null;
            try {
                versions.addAll(loaderInstaller.listLoaderVersions(loader, v.id()));
            } catch (Exception e) {
                err = e.getMessage();
            }
            final String errMsg = err;
            Platform.runLater(() -> {
                listBox.getChildren().clear();
                if (errMsg != null || versions.isEmpty()) {
                    Label e1 = new Label("获取失败: " + (errMsg == null ? "无可用版本" : errMsg));
                    e1.getStyleClass().add("hint");
                    listBox.getChildren().add(e1);
                    return;
                }
                // 数量标题
                Label count = new Label("共 " + versions.size() + " 个版本");
                count.setStyle("-fx-font-size: 12px; -fx-text-fill: " + Theme.TEXT_MUTED
                        + "; -fx-padding: 0 0 4 2;");
                listBox.getChildren().add(count);

                for (LoaderInstaller.LoaderVersion lv : versions) {
                    HBox row = new HBox(10);
                    row.setAlignment(Pos.CENTER_LEFT);
                    row.setPadding(new Insets(10, 14, 10, 14));
                    row.setMaxWidth(Double.MAX_VALUE);
                    row.setStyle("-fx-background-color: white; -fx-background-radius: 8;"
                            + "-fx-border-color: " + Theme.BORDER_LIGHT
                            + "; -fx-border-radius: 8; -fx-cursor: hand;");
                    Label verLbl = new Label(lv.version());
                    verLbl.setStyle("-fx-font-size: 13px; -fx-font-weight: bold;"
                            + "-fx-text-fill: " + Theme.TEXT + ";");
                    verLbl.setPrefWidth(140);
                    verLbl.setMinWidth(140);
                    Label badge = new Label(lv.stable() ? "稳定" : "测试");
                    badge.setStyle("-fx-font-size: 10.5px; -fx-text-fill: "
                            + (lv.stable() ? Theme.SUCCESS : Theme.WARN) + ";"
                            + "-fx-background-color: "
                            + (lv.stable() ? "#e6f5ea" : "#fef3e2") + ";"
                            + "-fx-background-radius: 4; -fx-padding: 1 7 1 7;");
                    Region spacer2 = new Region();
                    HBox.setHgrow(spacer2, Priority.ALWAYS);
                    Button pick = new Button("选择");
                    pick.setStyle("-fx-background-color: #e3eefb; -fx-text-fill: "
                            + Theme.PRIMARY + "; -fx-background-radius: 6;"
                            + "-fx-padding: 3 14 3 14; -fx-font-size: 12px;"
                            + "-fx-cursor: hand;");
                    final String chosen = lv.version();
                    pick.setOnAction(ev -> {
                        window.closeOverlay();
                        doInstallVersionWithLoader(v.id(), loader, chosen);
                    });
                    row.getChildren().addAll(verLbl, badge, spacer2, pick);
                    listBox.getChildren().add(row);
                }
            });
        });
    }

    // ---------------- 安装 ----------------

    private volatile boolean installing = false;

    /** 自动选加载器最新版本 */
    private void doInstallVersion(String mcVersion, LoaderInstaller.Loader loader) {
        doInstallVersionWithLoader(mcVersion, loader, null);
    }

    /**
     * 安装指定版本 + 加载器。
     *
     * @param loaderVersion 加载器版本，null 表示自动选最新
     */
    private void doInstallVersionWithLoader(String mcVersion,
                                            LoaderInstaller.Loader loader,
                                            String loaderVersion) {
        if (installing) return;
        installing = true;

        CompletableFuture.runAsync(() -> {
            try {
                VanillaInstaller.Progress prog = new VanillaInstaller.Progress() {
                    @Override
                    public void onStage(String stage) {
                        Platform.runLater(() -> window.setProgress(stage, 0, ""));
                    }
                    @Override
                    public void onProgress(long cur, long total) {
                        double f = total > 0 ? (double) cur / total : 0;
                        Platform.runLater(() -> window.setProgress(
                                "", f, String.format("%.0f%%", f * 100)));
                    }
                };

                // 先装原版
                List<VersionManager.VersionInfo> all = versionManager.listAllVersions();
                var target = all.stream().filter(x -> x.id().equals(mcVersion))
                        .findFirst().orElseThrow(() ->
                                new IllegalStateException("找不到版本 " + mcVersion));
                vanillaInstaller.install(mcVersion,
                        com.simplemc.launcher.util.Http.fetchJson(target.url()), prog);

                String finalVersionId = mcVersion;

                // 再装加载器
                if (loader != LoaderInstaller.Loader.VANILLA) {
                    Platform.runLater(() -> window.setProgress(
                            "安装 " + loader.display + "...", 0.9, ""));
                    List<JavaManager.JavaInfo> javas = JavaManager.detectAll();
                    JavaManager.JavaInfo jinfo = JavaManager.pickBest(javas,
                            JavaManager.requiredJavaMajor(mcVersion));
                    String javaBin = jinfo != null ? jinfo.path() : "java";

                    finalVersionId = switch (loader) {
                        case FABRIC -> loaderInstaller.installFabricLike(
                                LoaderInstaller.Loader.FABRIC, mcVersion, loaderVersion,
                                s -> Platform.runLater(() ->
                                        window.setProgress(s, 0.9, "")));
                        case QUILT -> loaderInstaller.installFabricLike(
                                LoaderInstaller.Loader.QUILT, mcVersion, loaderVersion,
                                s -> Platform.runLater(() ->
                                        window.setProgress(s, 0.9, "")));
                        case FORGE -> loaderInstaller.installForgeLike(
                                LoaderInstaller.Loader.FORGE, mcVersion, loaderVersion,
                                javaBin, s -> Platform.runLater(() ->
                                        window.setProgress(s, 0.9, "")));
                        case NEOFORGE -> loaderInstaller.installForgeLike(
                                LoaderInstaller.Loader.NEOFORGE, mcVersion, loaderVersion,
                                javaBin, s -> Platform.runLater(() ->
                                        window.setProgress(s, 0.9, "")));
                        default -> mcVersion;
                    };
                }

                final String vid = finalVersionId;
                config.set("current_version", vid).save();
                Platform.runLater(() -> {
                    window.setProgress("安装完成: " + vid, 1.0, "100%");
                    window.setStatus("安装完成");
                    alert(Alert.AlertType.INFORMATION, "安装完成: " + vid);
                });
            } catch (Exception e) {
                Log.error("安装失败", e);
                Platform.runLater(() -> {
                    window.setProgress("安装失败", 0, "0%");
                    alert(Alert.AlertType.ERROR, "安装失败:\n" + e.getMessage());
                });
            } finally {
                installing = false;
            }
        });
    }


    // ---------------- Modrinth 内容页 ----------------

    private VBox buildModrinthPage(String category) {
        VBox page = new VBox(10);
        page.setPadding(new Insets(16));

        // 标题行：标题 + 整合包专用的「导入本地整合包」按钮
        HBox titleRow = new HBox(10);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        Label title = new Label(category);
        title.setStyle("-fx-font-size: 17px; -fx-font-weight: bold; -fx-text-fill: "
                + Theme.PRIMARY + ";");
        Region titleSpacer = new Region();
        HBox.setHgrow(titleSpacer, Priority.ALWAYS);
        titleRow.getChildren().addAll(title, titleSpacer);

        if ("整合包".equals(category)) {
            Button importBtn = new Button("📂 导入本地整合包");
            importBtn.setStyle("-fx-background-color: " + Theme.PRIMARY + ";"
                    + "-fx-text-fill: white; -fx-background-radius: 6;"
                    + "-fx-padding: 7 16 7 16; -fx-font-size: 13px; -fx-cursor: hand;");
            importBtn.setOnAction(e -> doImportModpack());
            titleRow.getChildren().add(importBtn);
        }

        HBox searchRow = new HBox(8);
        searchRow.setAlignment(Pos.CENTER_LEFT);
        TextField search = new TextField();
        search.setPromptText("搜索" + category + "...");
        HBox.setHgrow(search, Priority.ALWAYS);
        Button searchBtn = new Button("搜索");
        searchBtn.getStyleClass().add("btn-primary");
        searchRow.getChildren().addAll(search, searchBtn);

        ScrollPane scroll = new ScrollPane();
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        VBox list = new VBox(8);
        scroll.setContent(list);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        page.getChildren().addAll(titleRow, searchRow, scroll);

        // 拖拽由 MainWindow 的全局处理接管（整窗口都能拖入），
        // 这里不再单独绑定，避免两处逻辑冲突。

        Modrinth.Category mc = switch (category) {
            case "模组" -> Modrinth.Category.MOD;
            case "整合包" -> Modrinth.Category.MODPACK;
            case "数据包" -> Modrinth.Category.DATAPACK;
            case "资源包" -> Modrinth.Category.RESOURCEPACK;
            case "光影包" -> Modrinth.Category.SHADER;
            case "世界" -> Modrinth.Category.WORLD;
            default -> null;
        };

        Runnable doSearch = () -> {
            String q = search.getText();
            list.getChildren().setAll(new Label("搜索中..."));
            CompletableFuture.runAsync(() -> {
                try {
                    List<Modrinth.Hit> hits;
                    if (mc == null) {
                        // 收藏夹：暂用模组搜索占位
                        hits = List.of();
                    } else {
                        String gameVer = config.getString("current_version");
                        String baseVer = new InstanceManager(Constants.MC_DIR)
                                .resolveBaseVersion(gameVer);
                        hits = Modrinth.search(mc, q, baseVer, null, 20);
                    }
                    Platform.runLater(() -> {
                        list.getChildren().clear();
                        if (hits.isEmpty()) {
                            Label empty = new Label("没有结果");
                            empty.getStyleClass().add("muted");
                            list.getChildren().add(empty);
                            return;
                        }
                        for (Modrinth.Hit h : hits) {
                            list.getChildren().add(makeModRow(h, category));
                        }
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        Label err = new Label("搜索失败: " + e.getMessage());
                        err.getStyleClass().add("hint");
                        list.getChildren().setAll(err);
                    });
                }
            });
        };

        searchBtn.setOnAction(e -> doSearch.run());
        search.setOnAction(e -> doSearch.run());
        Platform.runLater(doSearch);

        return page;
    }

    // ---------------- 整合包导入 ----------------

    /** 打开文件选择框导入本地整合包 */
    private void doImportModpack() {
        javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
        fc.setTitle("选择整合包文件");
        fc.getExtensionFilters().addAll(
                new javafx.stage.FileChooser.ExtensionFilter(
                        "整合包 (*.mrpack, *.zip)", "*.mrpack", "*.zip"),
                new javafx.stage.FileChooser.ExtensionFilter("所有文件", "*.*"));
        java.io.File file = fc.showOpenDialog(window.stage());
        if (file != null) {
            startModpackImport(file.toPath());
        }
    }

    /** 开始导入整合包（后台执行，覆盖层显示进度） */
    /** 供外部（全局拖拽）调用的公开入口 */
    public void importModpackFromPath(java.nio.file.Path archive) {
        if (archive == null) return;
        String name = archive.getFileName().toString().toLowerCase();
        if (!name.endsWith(".mrpack") && !name.endsWith(".zip")) {
            alert(Alert.AlertType.WARNING, "只支持 .mrpack 和 .zip 格式的整合包");
            return;
        }
        startModpackImport(archive);
    }

    private void startModpackImport(java.nio.file.Path archive) {
        if (importing) {
            alert(Alert.AlertType.INFORMATION, "已有导入任务正在进行，请稍候");
            return;
        }
        importing = true;

        // 覆盖层显示进度
        javafx.scene.layout.BorderPane overlay = new javafx.scene.layout.BorderPane();
        overlay.setStyle("-fx-background-color: " + Theme.BG + ";");

        VBox box = new VBox(14);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(40));

        Label title = new Label("正在导入整合包");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: "
                + Theme.PRIMARY + ";");

        Label fileName = new Label(archive.getFileName().toString());
        fileName.setStyle("-fx-font-size: 13px; -fx-text-fill: " + Theme.TEXT + ";");

        javafx.scene.control.ProgressIndicator spinner =
                new javafx.scene.control.ProgressIndicator();
        spinner.setPrefSize(48, 48);

        Label stageLabel = new Label("准备中...");
        stageLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: " + Theme.TEXT_MUTED + ";");
        stageLabel.setWrapText(true);
        stageLabel.setMaxWidth(600);

        box.getChildren().addAll(title, fileName, spinner, stageLabel);
        overlay.setCenter(box);
        window.showOverlay(overlay);

        CompletableFuture.runAsync(() -> {
            try {
                InstanceManager im = new InstanceManager(Constants.MC_DIR);
                String instanceId = im.importModpack(archive,
                        s -> Platform.runLater(() -> stageLabel.setText(s)));

                Platform.runLater(() -> {
                    window.closeOverlay();
                    importing = false;
                    String display = im.displayName(instanceId);
                    Alert a = new Alert(Alert.AlertType.INFORMATION);
                    a.setTitle("导入成功");
                    a.setHeaderText("整合包已导入为独立实例");
                    a.setContentText("名称: " + display + "\n实例 ID: " + instanceId
                            + "\n\n可以在「启动」页的「实例选择」中切换到这个实例。");
                    a.initOwner(window.stage());
                    a.showAndWait();
                    // 自动设为当前版本
                    config.set("current_version", instanceId).save();
                });
            } catch (Exception e) {
                // 打印完整堆栈到日志，便于定位导入失败原因
                Log.error("导入整合包失败: " + archive.getFileName()
                        + " -> " + Downloader.describe(e), e);
                String detail = Downloader.describe(e);
                Platform.runLater(() -> {
                    window.closeOverlay();
                    importing = false;
                    alert(Alert.AlertType.ERROR,
                            "导入失败:\n" + detail
                            + "\n\n详细信息已写入日志:\n" + Constants.LOG_FILE);
                });
            }
        });
    }

    private volatile boolean importing = false;

    private HBox makeModRow(Modrinth.Hit hit, String category) {
        HBox card = new HBox(12);
        card.getStyleClass().add("mod-card");
        card.setAlignment(Pos.CENTER_LEFT);

        // 图标容器：固定 48x48，带圆角裁剪，图片等比缩放居中
        StackPane iconBox = new StackPane();
        iconBox.setPrefSize(48, 48);
        iconBox.setMinSize(48, 48);
        iconBox.setMaxSize(48, 48);
        iconBox.setStyle("-fx-background-color: #eef3f9; -fx-background-radius: 6;");
        Label iconPlaceholder = new Label("📦");
        iconPlaceholder.setStyle("-fx-font-size: 20px;");
        iconBox.getChildren().add(iconPlaceholder);

        if (hit.iconUrl() != null && !hit.iconUrl().isBlank()) {
            final String iconUrl = hit.iconUrl();
            CompletableFuture.runAsync(() -> {
                try {
                    // Modrinth 图标是 .webp，JavaFX 的 Image 不支持 WebP，
                    // 必须走 ImageIO（配合 webp-imageio 依赖）解码后转成 JavaFX Image
                    byte[] data = loadImageBytes(iconUrl);
                    if (data == null || data.length == 0) {
                        Log.warn("图标下载失败: " + iconUrl);
                        return;
                    }
                    java.awt.image.BufferedImage buf = ImageIO.read(
                            new java.io.ByteArrayInputStream(data));
                    if (buf == null) {
                        Log.warn("图标解码失败（不支持的格式）: " + iconUrl);
                        return;
                    }
                    javafx.scene.image.WritableImage fxImg = toFxImage(buf, 48);
                    Platform.runLater(() -> {
                        ImageView iv = new ImageView(fxImg);
                        iv.setFitWidth(48);
                        iv.setFitHeight(48);
                        iv.setPreserveRatio(true);
                        iv.setSmooth(true);
                        javafx.scene.shape.Rectangle clip =
                                new javafx.scene.shape.Rectangle(48, 48);
                        clip.setArcWidth(12);
                        clip.setArcHeight(12);
                        iv.setClip(clip);
                        iconBox.getChildren().setAll(iv);
                    });
                } catch (Exception e) {
                    Log.warn("图标加载异常 " + iconUrl + ": " + e.getMessage());
                }
            });
        }

        VBox info = new VBox(3);
        HBox.setHgrow(info, Priority.ALWAYS);
        info.setMinWidth(0);        // 允许压缩，避免把按钮挤没
        Label name = new Label(hit.title());
        name.getStyleClass().add("mod-title");
        Label desc = new Label(hit.description());
        desc.getStyleClass().add("mod-desc");
        desc.setWrapText(true);
        desc.setMaxWidth(Double.MAX_VALUE);
        Label meta = new Label("⬇ " + Modrinth.formatDownloads(hit.downloads()));
        meta.getStyleClass().add("muted");
        info.getChildren().addAll(name, desc, meta);

        Button action = new Button("下载");
        action.getStyleClass().add("btn-primary");
        action.setStyle("-fx-padding: 5 16 5 16;");
        // 固定最小宽度，避免被文字区域挤压成 "..." 省略号
        action.setMinWidth(76);
        action.setPrefWidth(76);
        action.setMaxWidth(76);
        action.setOnAction(e -> doDownloadMod(hit, category));

        card.getChildren().addAll(iconBox, info, action);
        return card;
    }

    private void doDownloadMod(Modrinth.Hit hit, String category) {
        CompletableFuture.runAsync(() -> {
            try {
                Platform.runLater(() -> window.setProgress(
                        "获取 " + hit.title() + " 的下载地址...", 0.2, ""));

                String gameVer = config.getString("current_version");
                String baseVer = new InstanceManager(Constants.MC_DIR)
                        .resolveBaseVersion(gameVer);
                String loader = "";
                try {
                    var j = versionManager.readVersionJson(gameVer);
                    if (j.toString().toLowerCase().contains("fabric")) loader = "fabric";
                    else if (j.toString().toLowerCase().contains("forge")) loader = "forge";
                    else if (j.toString().toLowerCase().contains("quilt")) loader = "quilt";
                } catch (Exception ignored) {}

                Modrinth.FileEntry file = Modrinth.latestFile(hit.slug(), baseVer,
                        loader.isBlank() ? null : loader);
                if (file == null) {
                    Platform.runLater(() -> alert(Alert.AlertType.WARNING,
                            "没有找到适用于当前版本的 " + category));
                    return;
                }

                String sub = switch (category) {
                    case "模组" -> "mods";
                    case "资源包" -> "resourcepacks";
                    case "光影包" -> "shaderpacks";
                    case "数据包" -> "datapacks";
                    default -> "mods";
                };
                Path dest = Constants.MC_DIR.resolve(sub).resolve(file.filename());
                Files.createDirectories(dest.getParent());

                Platform.runLater(() -> window.setProgress(
                        "下载 " + file.filename() + "...", 0.3, ""));
                Downloader.download(List.of(file.url()), dest, (cur, total) -> {
                    double f = 0.3 + (total > 0 ? 0.7 * cur / total : 0.7);
                    Platform.runLater(() -> window.setProgress(
                            "下载 " + file.filename(), f,
                            String.format("%.0f%%", f * 100)));
                }, file.sha1());

                Platform.runLater(() -> {
                    window.setProgress("下载完成: " + file.filename(), 1.0, "100%");
                    alert(Alert.AlertType.INFORMATION, "已下载到:\n" + dest);
                });
            } catch (Exception e) {
                Log.error("下载失败", e);
                Platform.runLater(() -> {
                    window.setProgress("下载失败", 0, "0%");
                    alert(Alert.AlertType.ERROR, "下载失败:\n" + e.getMessage());
                });
            }
        });
    }

    // ---------------- 图标辅助 ----------------

    /** 下载图片字节（带 UA，跟随重定向） */
    private static byte[] loadImageBytes(String url) {
        try {
            java.net.http.HttpClient c = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10))
                    .followRedirects(java.net.http.HttpClient.Redirect.ALWAYS)
                    .build();
            java.net.http.HttpRequest req = java.net.http.HttpRequest
                    .newBuilder(java.net.URI.create(url))
                    .header("User-Agent", Constants.USER_AGENT)
                    .timeout(java.time.Duration.ofSeconds(15))
                    .GET().build();
            java.net.http.HttpResponse<byte[]> resp =
                    c.send(req, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) return null;
            return resp.body();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * BufferedImage -> JavaFX Image（顺便做高质量缩放）。
     */
    private static javafx.scene.image.WritableImage toFxImage(
            java.awt.image.BufferedImage buf, int size) {
        // 先缩放到目标尺寸（保持宽高比，居中填充正方形）
        java.awt.image.BufferedImage scaled =
                new java.awt.image.BufferedImage(size, size,
                        java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = scaled.createGraphics();
        try {
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING,
                    java.awt.RenderingHints.VALUE_RENDER_QUALITY);
            int sw = buf.getWidth(), sh = buf.getHeight();
            double scale = Math.min((double) size / sw, (double) size / sh);
            int dw = Math.max(1, (int) Math.round(sw * scale));
            int dh = Math.max(1, (int) Math.round(sh * scale));
            int dx = (size - dw) / 2, dy = (size - dh) / 2;
            g.drawImage(buf, dx, dy, dw, dh, null);
        } finally {
            g.dispose();
        }
        javafx.scene.image.WritableImage fx =
                new javafx.scene.image.WritableImage(size, size);
        javafx.scene.image.PixelWriter pw = fx.getPixelWriter();
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int argb = scaled.getRGB(x, y);
                pw.setArgb(x, y, argb);
            }
        }
        return fx;
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
