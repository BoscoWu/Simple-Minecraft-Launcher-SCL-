package com.simplemc.launcher.ui.page;

import com.simplemc.launcher.core.InstancePaths;
import com.simplemc.launcher.core.Modrinth;
import com.simplemc.launcher.core.VersionManager;
import com.simplemc.launcher.ui.Theme;
import com.simplemc.launcher.util.Log;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 版本选择页：点「下载」后先选一个目标版本。
 *
 * <p>顺序约定：</p>
 * <ol>
 *   <li><b>当前适配的版本</b>——已安装 + 该项目支持，排在最前并加标记</li>
 *   <li>其余已安装版本——按从新到旧排序</li>
 *   <li>未安装但项目支持的版本——排在最后，点击会先提示去装游戏</li>
 * </ol>
 */
public final class VersionPicker {

    /** 选择结果回调 */
    public interface Callback {
        /**
         * @param versionId 选中的版本 ID（已安装的实例 ID）
         * @param isInstalled 该版本是否已安装
         */
        void onPick(String versionId, boolean isInstalled);
    }

    private final VersionManager versionManager = new VersionManager(null);

    /**
     * 构建版本选择页内容。
     *
     * @param title     页面标题（如 "选择安装模组的版本"）
     * @param hit       当前的 Modrinth 项目
     * @param installed 已安装的版本 ID（按从新到旧）
     * @param onBack    点返回时执行
     * @param onPick    选中版本时执行
     */
    public VBox build(String title, Modrinth.Hit hit, List<String> installed,
                      Runnable onBack, Callback onPick) {

        VBox page = new VBox(10);
        page.setPadding(new Insets(16));

        // ---------- 顶部：返回 + 标题 ----------
        HBox top = new HBox(10);
        top.setAlignment(Pos.CENTER_LEFT);
        Button back = new Button("←");
        back.setStyle("-fx-background-color: transparent; -fx-font-size: 18px;"
                + "-fx-text-fill: " + Theme.TEXT_MUTED + "; -fx-cursor: hand;"
                + "-fx-padding: 0 8 0 0;");
        back.setOnAction(e -> onBack.run());
        Label titleLbl = new Label(title);
        titleLbl.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;"
                + "-fx-text-fill: " + Theme.TEXT + ";");
        top.getChildren().addAll(back, titleLbl);
        page.getChildren().add(top);

        // ---------- 项目信息 ----------
        Label projLbl = new Label(hit.title());
        projLbl.setStyle("-fx-font-size: 13px; -fx-text-fill: " + Theme.TEXT_MUTED + ";");
        page.getChildren().add(projLbl);

        // ---------- 列表（先显示加载中）----------
        Label loading = new Label("正在获取 " + hit.title() + " 支持的版本...");
        loading.setStyle("-fx-font-size: 12.5px; -fx-text-fill: " + Theme.TEXT_MUTED + ";");

        VBox listBox = new VBox(6);
        listBox.setFillWidth(true);
        listBox.getChildren().add(loading);

        ScrollPane scroll = new ScrollPane(listBox);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        page.getChildren().add(scroll);

        // ---------- 异步加载支持版本并渲染 ----------
        CompletableFuture.runAsync(() -> {
            List<String> supported = new ArrayList<>();
            String err = null;
            try {
                supported.addAll(Modrinth.supportedGameVersions(hit.slug()));
            } catch (Exception e) {
                err = e.getMessage();
            }
            final List<String> sup = supported;
            final String errMsg = err;
            Platform.runLater(() -> {
                listBox.getChildren().clear();
                if (errMsg != null) {
                    Label e1 = new Label("获取失败: " + errMsg);
                    e1.setStyle("-fx-text-fill: " + Theme.WARN + ";");
                    listBox.getChildren().add(e1);
                    return;
                }
                renderList(listBox, sup, installed, onPick);
            });
        });

        return page;
    }

    /** 渲染版本列表 */
    private void renderList(VBox listBox, List<String> supported,
                            List<String> installed, Callback onPick) {

        // 1. 按基版本归组：同一个 MC 版本可能装了好几个实例（原版 + 各加载器）
        java.util.Map<String, List<String>> baseToInstances =
                new java.util.LinkedHashMap<>();
        List<String> installedBase = new ArrayList<>();
        for (String id : installed) {
            String base = InstancePaths.baseVersionOf(id);
            if (!installedBase.contains(base)) installedBase.add(base);
            baseToInstances.computeIfAbsent(base, k -> new ArrayList<>()).add(id);
        }

        // 2. 当前适配 = 已安装 ∩ 项目支持
        List<String> goodInstalled = new ArrayList<>();
        for (String base : installedBase) {
            if (supported.contains(base)) goodInstalled.add(base);
        }

        // 3. 未安装但项目支持的版本
        List<String> notInstalled = new ArrayList<>();
        for (String s : supported) {
            if (!installedBase.contains(s) && !notInstalled.contains(s)) {
                notInstalled.add(s);
            }
        }

        if (goodInstalled.isEmpty() && notInstalled.isEmpty()
                && installedBase.isEmpty()) {
            Label none = new Label("该项目没有任何可用版本");
            none.setStyle("-fx-text-fill: " + Theme.TEXT_MUTED + ";");
            listBox.getChildren().add(none);
            return;
        }

        // ---------- 「当前适配的版本」 ----------
        // 同一个 MC 版本可能有多个实例（原版 + Forge + Fabric…）。
        // 推荐项只给一个：优先原版（没有加载器依赖），否则第一个。
        if (!goodInstalled.isEmpty()) {
            listBox.getChildren().add(sectionHeader("当前适配的版本", Theme.PRIMARY));

            // 找出推荐实例
            String recommendedId = null;
            for (String base : goodInstalled) {
                for (String id : baseToInstances.get(base)) {
                    if (InstancePaths.loaderOf(id).isBlank()) {
                        recommendedId = id;
                        break;
                    }
                }
                if (recommendedId != null) break;
            }
            if (recommendedId == null && !goodInstalled.isEmpty()) {
                recommendedId = baseToInstances.get(goodInstalled.get(0)).get(0);
            }

            for (String base : goodInstalled) {
                for (String instanceId : baseToInstances.get(base)) {
                    listBox.getChildren().add(makeRow(instanceId, base,
                            true, instanceId.equals(recommendedId), onPick));
                }
            }
        }

        // ---------- 「其他已安装版本」 ----------
        List<String> otherInstalled = new ArrayList<>();
        for (String base : installedBase) {
            if (!goodInstalled.contains(base)) otherInstalled.add(base);
        }
        if (!otherInstalled.isEmpty()) {
            listBox.getChildren().add(sectionHeader(
                    "其他已安装版本（该项目可能不兼容）", Theme.TEXT_MUTED));
            for (String base : otherInstalled) {
                for (String instanceId : baseToInstances.get(base)) {
                    listBox.getChildren().add(makeRow(instanceId, base,
                            true, false, onPick));
                }
            }
        }

        // ---------- 「尚未安装」 ----------
        if (!notInstalled.isEmpty()) {
            listBox.getChildren().add(sectionHeader(
                    "支持但尚未安装的版本（需先到「游戏」页安装）", Theme.TEXT_MUTED));
            int limit = Math.min(notInstalled.size(), 20);
            for (int i = 0; i < limit; i++) {
                String v = notInstalled.get(i);
                listBox.getChildren().add(makeRow(v, v, false, false, onPick));
            }
            if (notInstalled.size() > limit) {
                Label more = new Label("还有 " + (notInstalled.size() - limit) + " 个版本未显示");
                more.setStyle("-fx-font-size: 11.5px; -fx-text-fill: "
                        + Theme.TEXT_MUTED + "; -fx-padding: 4 0 0 4;");
                listBox.getChildren().add(more);
            }
        }
    }

    private Label sectionHeader(String text, String color) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 12px; -fx-font-weight: bold;"
                + "-fx-text-fill: " + color + "; -fx-padding: 6 0 2 2;");
        return l;
    }

    /** 单行版本条目 */
    private HBox makeRow(String versionId, String baseVersion, boolean installed,
                         boolean recommended, Callback onPick) {
        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10, 14, 10, 14));
        row.setMaxWidth(Double.MAX_VALUE);

        String bg = recommended ? "#e9f2fb" : "white";
        String border = recommended ? Theme.PRIMARY : Theme.BORDER_LIGHT;
        double bw = recommended ? 1.5 : 1;
        row.setStyle("-fx-background-color: " + bg + "; -fx-background-radius: 8;"
                + "-fx-border-color: " + border + "; -fx-border-radius: 8;"
                + "-fx-border-width: " + bw + "; -fx-cursor: "
                + (installed ? "hand" : "default") + ";");

        // 版本名（实例 ID 可能很长，给足宽度并允许省略号）
        Label name = new Label(versionId);
        name.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: "
                + (installed ? Theme.TEXT : Theme.TEXT_MUTED) + ";");
        name.setPrefWidth(300);
        name.setMinWidth(180);
        name.setMaxWidth(300);
        name.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        name.setTooltip(new javafx.scene.control.Tooltip(versionId));

        // 实际 MC 版本（实例 ID 和基版本不同时显示）
        Label sub = new Label(versionId.equals(baseVersion) ? "" : baseVersion);
        sub.setStyle("-fx-font-size: 11.5px; -fx-text-fill: " + Theme.TEXT_MUTED + ";");
        sub.setPrefWidth(80);
        sub.setMinWidth(80);
        sub.setMaxWidth(80);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // 徽章
        HBox badges = new HBox(6);
        badges.setAlignment(Pos.CENTER_RIGHT);
        if (recommended) {
            badges.getChildren().add(badge("推荐", Theme.PRIMARY, "#e3eefb"));
        }
        if (installed) {
            badges.getChildren().add(badge("已安装", Theme.SUCCESS, "#e6f5ea"));
        } else {
            badges.getChildren().add(badge("未安装", Theme.WARN, "#fef3e2"));
        }

        row.getChildren().addAll(name, sub, spacer, badges);

        if (installed) {
            row.setOnMouseClicked(e -> onPick.onPick(versionId, true));
            row.setOnMouseEntered(e -> row.setStyle(
                    "-fx-background-color: #f2f7fd; -fx-background-radius: 8;"
                    + "-fx-border-color: " + (recommended ? Theme.PRIMARY
                    : Theme.PRIMARY_HOVER) + "; -fx-border-radius: 8;"
                    + "-fx-border-width: " + bw + "; -fx-cursor: hand;"));
            row.setOnMouseExited(e -> row.setStyle(
                    "-fx-background-color: " + bg + "; -fx-background-radius: 8;"
                    + "-fx-border-color: " + border + "; -fx-border-radius: 8;"
                    + "-fx-border-width: " + bw + "; -fx-cursor: hand;"));
        }
        return row;
    }

    private Label badge(String text, String color, String bg) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 10.5px; -fx-text-fill: " + color + ";"
                + "-fx-background-color: " + bg + "; -fx-background-radius: 4;"
                + "-fx-padding: 1 7 1 7;");
        return l;
    }
}
