package com.simplemc.launcher;

import com.simplemc.launcher.ui.MainWindow;
import javafx.application.Application;
import javafx.stage.Stage;

/**
 * 程序入口。
 */
public class Main extends Application {

    @Override
    public void start(Stage stage) {
        new MainWindow(stage);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
