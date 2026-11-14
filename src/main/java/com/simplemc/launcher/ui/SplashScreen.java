package com.simplemc.launcher.ui;

import com.simplemc.launcher.util.Constants;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.io.InputStream;

/**
 * 启动闪屏：在加载期间显示，主窗口准备好后自动关闭。
 */
public final class SplashScreen {

    private static final int W = 380;
    private static final int H = 260;

    private final Stage stage = new Stage(StageStyle.UNDECORATED);
    private final Label status = new Label("正在启动...");
    private final ProgressBar bar = new ProgressBar(0.35);

    public SplashScreen() {
        VBox box = new VBox(12);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(24));
        box.setStyle("-fx-background-color: white;"
                + "-fx-border-color: " + Theme.BORDER + "; -fx-border-width: 1;");

        // 图标
        try (InputStream in = SplashScreen.class.getResourceAsStream("/icon.png")) {
            if (in != null) {
                ImageView iv = new ImageView(new Image(in, 72, 72, true, true));
                box.getChildren().add(iv);
            }
        } catch (Exception ignored) {}

        Label title = new Label(Constants.APP_TITLE);
        title.getStyleClass().add("splash-title");

        status.getStyleClass().add("splash-status");

        bar.setPrefWidth(240);
        bar.setPrefHeight(8);

        Label ver = new Label(Constants.VERSION);
        ver.setStyle("-fx-font-size: 11px; -fx-text-fill: #9aa0a6;");

        box.getChildren().addAll(title, status, bar, ver);

        Scene scene = new Scene(box, W, H);
        scene.getStylesheets().add("data:text/css;base64," + java.util.Base64.getEncoder()
                .encodeToString(Theme.CSS.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
        stage.setScene(scene);
        stage.setAlwaysOnTop(true);
        stage.centerOnScreen();

        try (InputStream in = SplashScreen.class.getResourceAsStream("/icon.png")) {
            if (in != null) stage.getIcons().add(new Image(in));
        } catch (Exception ignored) {}
    }

    public void show() {
        stage.show();
    }

    public void setStatus(String text) {
        Platform.runLater(() -> {
            if (status != null) status.setText(text);
        });
    }

    public void close() {
        Platform.runLater(() -> {
            try {
                stage.close();
            } catch (Exception ignored) {}
        });
    }
}
