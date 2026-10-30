package com.simplemc.launcher;

/**
 * 打包用启动器。
 *
 * <p>JavaFX 要求：如果主类继承 {@link javafx.application.Application}，
 * JVM 会检查 JavaFX 运行时是否以模块方式加载，否则报
 * "缺少 JavaFX 运行时组件"。用一个不继承 Application 的中转类
 * 调用 {@code Main.main()}，就能绕过该检查，让 shaded jar +
 * 内置 runtime 的免安装包正常工作。
 */
public final class Launcher {

    public static void main(String[] args) {
        Main.main(args);
    }

    private Launcher() {
    }
}
