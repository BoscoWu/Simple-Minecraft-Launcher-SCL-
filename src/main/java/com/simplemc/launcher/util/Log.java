package com.simplemc.launcher.util;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/**
 * 简洁日志：同时输出到控制台、文件，并可转发给界面。
 */
public final class Log {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static volatile Consumer<String> uiSink;
    private static volatile boolean fileReady;

    private Log() {}

    public static void setUiSink(Consumer<String> sink) {
        uiSink = sink;
    }

    public static void info(String msg) { write("INFO", msg, null); }
    public static void warn(String msg) { write("WARN", msg, null); }
    public static void error(String msg) { write("ERROR", msg, null); }
    public static void error(String msg, Throwable t) { write("ERROR", msg, t); }

    private static synchronized void write(String level, String msg, Throwable t) {
        String time = LocalDateTime.now().format(FMT);
        String line = time + " [" + level + "] " + msg;

        // 异常堆栈也要写进文件，否则日志里看不到失败原因
        String trace = "";
        if (t != null) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            trace = sw.toString();
        }

        // 控制台
        if ("ERROR".equals(level)) {
            System.err.println(line);
        } else {
            System.out.println(line);
        }
        if (!trace.isEmpty()) {
            System.err.println(trace);
        }

        // 文件
        try {
            if (!fileReady) {
                Files.createDirectories(Constants.DATA_DIR);
                fileReady = true;
            }
            StringBuilder sb = new StringBuilder(line).append(System.lineSeparator());
            if (!trace.isEmpty()) {
                sb.append(trace);
                if (!trace.endsWith(System.lineSeparator())) {
                    sb.append(System.lineSeparator());
                }
            }
            Files.writeString(Constants.LOG_FILE, sb.toString(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }

        // 界面
        Consumer<String> sink = uiSink;
        if (sink != null) {
            try {
                sink.accept(line);
            } catch (Exception ignored) {
            }
        }
    }
}
