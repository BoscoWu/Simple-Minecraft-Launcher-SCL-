package com.simplemc.launcher.ui;

import javafx.scene.paint.Color;

/**
 * 界面配色与尺寸常量（与 Python 版保持一致）。
 */
public final class Theme {

    private Theme() {}

    // 主色
    public static final String PRIMARY = "#3572b0";
    public static final String PRIMARY_HOVER = "#2a5d94";
    public static final String PRIMARY_LIGHT = "#4a8ad4";

    // 背景
    public static final String BG = "#f0f4f8";
    public static final String WHITE = "#ffffff";
    public static final String SIDEBAR = "#ffffff";

    // 文字
    public static final String TEXT = "#1a202c";
    public static final String TEXT_MUTED = "#6b7684";
    public static final String TEXT_ON_PRIMARY = "#ffffff";
    public static final String TEXT_ON_PRIMARY_DIM = "#dce8f5";

    // 状态
    public static final String SUCCESS = "#2FA84F";
    public static final String DANGER = "#c94f4f";
    public static final String DANGER_HOVER = "#a83c3c";
    public static final String WARN = "#d97706";
    public static final String GRAY_BTN = "#9aa0a6";
    public static final String GRAY_HOVER = "#7d8388";

    // 边框
    public static final String BORDER = "#d8dee6";
    public static final String BORDER_LIGHT = "#e8edf2";

    // 圆角
    /**
     * 圆角半径。
     * Windows 10 用直角（0）；Windows 11 / macOS / Linux 用圆角。
     */
    public static final boolean ROUNDED =
            com.simplemc.launcher.util.Constants.useRoundedCorners();
    public static final double RADIUS = ROUNDED ? 8 : 0;
    public static final double RADIUS_SM = ROUNDED ? 6 : 0;

    public static Color color(String hex) {
        return Color.web(hex);
    }

    /** 主样式表（内联，避免外部资源找不到） */
    public static final String CSS = """
        .root {
            -fx-font-family: "Microsoft YaHei UI", "Segoe UI", "PingFang SC", sans-serif;
            -fx-font-size: 13px;
            -fx-background-color: %BG%;
        }

        /* ---------- 顶部栏 ---------- */
        .titlebar {
            -fx-background-color: %PRIMARY%;
            -fx-pref-height: 44;
            -fx-min-height: 44;
        }
        .titlebar-label {
            -fx-text-fill: white;
            -fx-font-size: 17px;
            -fx-font-weight: bold;
        }
        .nav-btn {
            -fx-background-color: transparent;
            -fx-text-fill: %TEXT_ON_PRIMARY_DIM%;
            -fx-font-size: 14px;
            -fx-padding: 6 16 6 16;
            -fx-background-radius: %RAD%;
            -fx-cursor: hand;
        }
        .nav-btn:hover {
            -fx-background-color: %PRIMARY_LIGHT%;
            -fx-text-fill: white;
        }
        .nav-btn-active {
            -fx-background-color: %PRIMARY_LIGHT%;
            -fx-text-fill: white;
        }
        .win-btn {
            -fx-background-color: transparent;
            -fx-text-fill: white;
            -fx-font-size: 15px;
            -fx-pref-width: 44;
            -fx-pref-height: 44;
            -fx-background-radius: 0;
            -fx-cursor: hand;
        }
        .win-btn:hover {
            -fx-background-color: %PRIMARY_LIGHT%;
        }
        .win-btn-close:hover {
            -fx-background-color: #e81123;
        }

        /* ---------- 侧边栏 ---------- */
        .sidebar {
            -fx-background-color: white;
            -fx-pref-width: 220;
            -fx-min-width: 220;
            -fx-padding: 16;
            -fx-border-color: transparent %BORDER_LIGHT% transparent transparent;
            -fx-border-width: 0 1 0 0;
        }
        .avatar {
            -fx-background-radius: %RAD%;
        }
        .player-name {
            -fx-font-size: 16px;
            -fx-font-weight: bold;
            -fx-text-fill: %TEXT%;
        }
        .player-status {
            -fx-font-size: 12px;
            -fx-text-fill: %TEXT_MUTED%;
        }

        /* ---------- 按钮 ---------- */
        .btn-primary {
            -fx-background-color: %PRIMARY%;
            -fx-text-fill: white;
            -fx-background-radius: %RADIUS%;
            -fx-padding: 7 18 7 18;
            -fx-font-size: 13px;
            -fx-cursor: hand;
        }
        .btn-primary:hover { -fx-background-color: %PRIMARY_HOVER%; }
        .btn-primary:disabled { -fx-opacity: 0.55; }

        .btn-default {
            -fx-background-color: #e8eef6;
            -fx-text-fill: %PRIMARY%;
            -fx-background-radius: %RADIUS%;
            -fx-padding: 7 18 7 18;
            -fx-font-size: 13px;
            -fx-cursor: hand;
        }
        .btn-default:hover { -fx-background-color: #d5e3f3; }

        .btn-danger {
            -fx-background-color: %DANGER%;
            -fx-text-fill: white;
            -fx-background-radius: %RADIUS%;
            -fx-padding: 7 18 7 18;
            -fx-font-size: 13px;
            -fx-cursor: hand;
        }
        .btn-danger:hover { -fx-background-color: %DANGER_HOVER%; }

        .btn-gray {
            -fx-background-color: %GRAY_BTN%;
            -fx-text-fill: white;
            -fx-background-radius: %RADIUS%;
            -fx-padding: 6 14 6 14;
            -fx-font-size: 12px;
            -fx-cursor: hand;
        }
        .btn-gray:hover { -fx-background-color: %GRAY_HOVER%; }

        .btn-launch {
            -fx-background-color: white;
            -fx-border-color: %PRIMARY%;
            -fx-border-radius: %RADIUS%;
            -fx-border-width: 2;
            -fx-text-fill: %PRIMARY%;
            -fx-font-size: 18px;
            -fx-font-weight: bold;
            -fx-background-radius: %RADIUS%;
            -fx-padding: 12 0 12 0;
            -fx-cursor: hand;
            -fx-pref-height: 56;
        }
        .btn-launch:hover { -fx-background-color: #eaf2fb; }

        /* ---------- 下载页左侧分类 ---------- */
        .dl-nav {
            -fx-background-color: white;
            -fx-pref-width: 170;
            -fx-min-width: 170;
            -fx-padding: 12 8 12 8;
        }
        /* 所有分类按钮统一尺寸：高度固定，宽度撑满侧栏 */
        .dl-nav-item {
            -fx-background-color: transparent;
            -fx-text-fill: %TEXT%;
            -fx-font-size: 14px;
            -fx-padding: 0 12 0 12;
            -fx-background-radius: %RAD%;
            -fx-alignment: CENTER_LEFT;
            -fx-cursor: hand;
            -fx-min-width: 0;
            -fx-pref-width: 154;
            -fx-max-width: infinity;
            -fx-min-height: 36;
            -fx-pref-height: 36;
            -fx-max-height: 36;
        }
        .dl-nav-item:hover { -fx-background-color: #eef3f9; }
        .dl-nav-item-active {
            -fx-background-color: #e3eefb;
            -fx-text-fill: %PRIMARY%;
            -fx-font-weight: bold;
        }

        /* ---------- 卡片 ---------- */
        .card {
            -fx-background-color: white;
            -fx-background-radius: %RADIUS%;
            -fx-border-color: %BORDER_LIGHT%;
            -fx-border-radius: %RADIUS%;
            -fx-border-width: 1;
        }
        .card-title {
            -fx-font-size: 14px;
            -fx-font-weight: bold;
            -fx-text-fill: %TEXT%;
        }
        .mod-card {
            -fx-background-color: white;
            -fx-background-radius: %RADIUS%;
            -fx-border-color: %BORDER_LIGHT%;
            -fx-border-radius: %RADIUS%;
            -fx-border-width: 1;
            -fx-padding: 12;
        }
        .mod-card:hover {
            -fx-border-color: #b9d3ef;
            -fx-effect: dropshadow(gaussian, rgba(53,114,176,0.14), 10, 0, 0, 2);
        }
        .mod-title {
            -fx-font-size: 14px;
            -fx-font-weight: bold;
            -fx-text-fill: %TEXT%;
        }
        .mod-desc {
            -fx-font-size: 12px;
            -fx-text-fill: %TEXT_MUTED%;
        }

        /* ---------- 输入框 ---------- */
        .text-field, .combo-box, .text-area {
            -fx-background-radius: %RAD%;
            -fx-border-radius: %RAD%;
            -fx-border-color: %BORDER%;
            -fx-border-width: 1;
            -fx-background-color: white;
            -fx-padding: 6 10 6 10;
        }
        .text-field:focused {
            -fx-border-color: %PRIMARY%;
            -fx-border-width: 1.5;
        }

        /* ---------- 列表 ---------- */
        .server-row {
            -fx-background-color: transparent;
            -fx-padding: 6 8 6 8;
        }
        .server-row:hover { -fx-background-color: #f5f9fd; }

        /* ---------- 滚动条 ---------- */
        .scroll-bar:vertical {
            -fx-background-color: transparent;
            -fx-pref-width: 10;
        }
        .scroll-bar .thumb {
            -fx-background-color: #c6ccd4;
            -fx-background-radius: %RAD%;
        }
        .scroll-bar .thumb:hover { -fx-background-color: #a8b0ba; }
        .scroll-bar .increment-button,
        .scroll-bar .decrement-button {
            -fx-background-color: transparent;
            -fx-pref-height: 0;
        }
        .list-view { -fx-background-color: transparent; -fx-border-color: transparent; }

        /* ---------- 进度 ---------- */
        .progress-bar > .track {
            -fx-background-color: #e4e9ef;
            -fx-background-radius: %RAD%;
        }
        .progress-bar > .bar {
            -fx-background-color: %PRIMARY%;
            -fx-background-radius: %RAD%;
        }

        /* ---------- 分区标题 ---------- */
        .section-title {
            -fx-font-size: 15px;
            -fx-font-weight: bold;
            -fx-text-fill: %PRIMARY%;
        }
        .muted { -fx-text-fill: %TEXT_MUTED%; -fx-font-size: 12px; }
        .hint { -fx-text-fill: #b45309; -fx-font-size: 12px; }

        /* loading overlay */
        .overlay-bg { -fx-background-color: rgba(255,255,255,0.92); }
        .splash-title { -fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: %PRIMARY%; }
        .splash-status { -fx-font-size: 12px; -fx-text-fill: %TEXT_MUTED%; }
        """.replace("%BG%", BG)
            .replace("%PRIMARY%", PRIMARY)
            .replace("%PRIMARY_HOVER%", PRIMARY_HOVER)
            .replace("%PRIMARY_LIGHT%", PRIMARY_LIGHT)
            .replace("%TEXT%", TEXT)
            .replace("%TEXT_MUTED%", TEXT_MUTED)
            .replace("%TEXT_ON_PRIMARY_DIM%", TEXT_ON_PRIMARY_DIM)
            .replace("%DANGER%", DANGER)
            .replace("%DANGER_HOVER%", DANGER_HOVER)
            .replace("%GRAY_BTN%", GRAY_BTN)
            .replace("%GRAY_HOVER%", GRAY_HOVER)
            .replace("%BORDER%", BORDER)
            .replace("%BORDER_LIGHT%", BORDER_LIGHT)
            .replace("%RAD%", String.valueOf((int) RADIUS_SM))
            .replace("%RADIUS%", String.valueOf((int) RADIUS));
}
