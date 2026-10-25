package com.simplemc.launcher.util;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 路径常量与启动器全局信息。
 */
public final class AppPaths {

    public static final String VERSION = "3.1.0";
    public static final String USER_AGENT = "SimpleMCLauncher/" + VERSION;
    public static final String CLIENT_ID = "8d7c60d0-fddf-464c-a9f4-d190f1daa576";
    public static final String REDIRECT_URL = "https://login.microsoftonline.com/common/oauth2/nativeclient";

    public static final Path SCRIPT_DIR;
    public static final Path PYLAUNCHER_DIR;
    public static final Path MC_DIR;
    public static final Path SERVER_DIR;
    public static final Path JAVA_DIR;
    public static final Path CONFIG_FILE;
    public static final Path LOG_FILE;
    public static final Path FRP_DIR;

    static {
        SCRIPT_DIR = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        PYLAUNCHER_DIR = SCRIPT_DIR.resolve("pyLauncher");
        MC_DIR = PYLAUNCHER_DIR.resolve(".minecraft");
        SERVER_DIR = PYLAUNCHER_DIR.resolve("servers");
        JAVA_DIR = PYLAUNCHER_DIR.resolve("java");
        CONFIG_FILE = PYLAUNCHER_DIR.resolve("launcher_config.json");
        LOG_FILE = PYLAUNCHER_DIR.resolve("launcher.log");
        FRP_DIR = PYLAUNCHER_DIR.resolve("frp");
    }

    private AppPaths() {
    }
}