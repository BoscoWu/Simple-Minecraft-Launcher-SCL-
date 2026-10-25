package com.simplemc.launcher.util;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 日志：同时输出到控制台与 launcher.log 文件。
 */
public final class Log {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static PrintWriter writer;
    private static volatile boolean initialized;

    public static synchronized void init(Path logFile) {
        if (initialized) {
            return;
        }
        try {
            Files.createDirectories(logFile.getParent());
            writer = new PrintWriter(new OutputStreamWriter(
                    new FileOutputStream(logFile.toFile(), true), StandardCharsets.UTF_8), true);
        } catch (IOException e) {
            writer = null;
        }
        initialized = true;
    }

    public static void info(String msg) {
        write("INFO", msg, null);
    }

    public static void warn(String msg) {
        write("WARN", msg, null);
    }

    public static void error(String msg) {
        write("ERROR", msg, null);
    }

    public static void error(String msg, Throwable t) {
        write("ERROR", msg, t);
    }

    private static synchronized void write(String level, String msg, Throwable t) {
        String line = LocalDateTime.now().format(FMT) + " [" + level + "] " + msg;
        if (writer != null) {
            writer.println(line);
        }
        System.out.println(line);
        if (t != null) {
            if (writer != null) {
                t.printStackTrace(writer);
            }
            t.printStackTrace(System.out);
        }
    }

    private Log() {
    }
}
