package com.simplemc.launcher.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Python 桥。
 *
 * <p>启动器用 Java 做界面与下载，把「纯逻辑」的部分交给 Python 脚本
 * （{@code resources/py/scl_helper.py}）处理，实现多语言混编。</p>
 *
 * <p><b>必须优雅降级</b>：如果机器上没有 Python，或者脚本执行超时，
 * 调用方要能回退到 Java 自己的实现，绝不能让功能不可用。</p>
 *
 * <p>通信方式：标准输入写 JSON 请求，标准输出读 JSON 响应。</p>
 */
public final class PyBridge {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 单次调用超时。分类大清单可能要几秒，给足余量 */
    private static final int TIMEOUT_SECONDS = 20;

    /** 检测结果缓存：null = 还没检测过 */
    private static volatile Boolean available;
    private static volatile String pythonExe;
    private static volatile Path scriptPath;
    private static volatile boolean hasPillow;

    private PyBridge() {}

    /** Python 是否可用（结果缓存） */
    public static boolean isAvailable() {
        if (available == null) {
            synchronized (PyBridge.class) {
                if (available == null) {
                    detect();
                }
            }
        }
        return Boolean.TRUE.equals(available);
    }

    /** 返回 Python 版本，用于设置页展示；不可用返回 null */
    public static String pythonVersion() {
        if (!isAvailable()) return null;
        JsonNode r = call("{\"action\":\"ping\"}");
        if (r == null || !r.path("ok").asBoolean(false)) return null;
        return r.path("version").asText();
    }

    private static void detect() {
        // 1. 找 Python 可执行文件
        List<String> candidates = new ArrayList<>();
        String env = System.getenv("SCL_PYTHON");
        if (env != null && !env.isBlank()) candidates.add(env);
        for (String name : new String[]{"python", "python3", "py"}) {
            String found = which(name);
            if (found != null && !candidates.contains(found)) candidates.add(found);
        }

        // 2. 释放脚本到临时目录
        scriptPath = extractScript();
        if (scriptPath == null) {
            available = false;
            Log.warn("Python 桥：脚本释放失败，将只使用 Java 实现");
            return;
        }

        // 3. 逐个试跑 ping。优先选带 Pillow 的解释器（能解码 WebP 图标）
        String fallback = null;
        for (String exe : candidates) {
            PingResult r = probe(exe);
            if (!r.ok) continue;
            if (r.pillow) {
                pythonExe = exe;
                available = true;
                hasPillow = true;
                Log.info("Python 桥已启用: " + exe + " (含 Pillow，可解码 WebP 图标)");
                return;
            }
            if (fallback == null) fallback = exe;
        }
        if (fallback != null) {
            pythonExe = fallback;
            available = true;
            hasPillow = false;
            Log.info("Python 桥已启用: " + fallback + " (无 Pillow，图标使用占位图)");
            return;
        }
        available = false;
        Log.info("Python 桥：未检测到可用的 Python，使用纯 Java 实现");
    }

    /** 是否具备 Pillow（能解码 WebP） */
    public static boolean hasPillow() {
        isAvailable();
        return hasPillow;
    }

    /**
     * 用 Python 解码 WebP 并转成 PNG。
     *
     * @param webpBytes 原始 WebP 数据
     * @param size      目标边长
     * @return PNG 字节；失败返回 null（调用方用占位图）
     */
    public static byte[] decodeWebp(byte[] webpBytes, int size) {
        if (!hasPillow() || webpBytes == null || webpBytes.length == 0) return null;
        if (webpBytes.length > 2_000_000) return null;   // 图标不该这么大
        try {
            String b64 = java.util.Base64.getEncoder().encodeToString(webpBytes);
            var req = new com.fasterxml.jackson.databind.ObjectMapper()
                    .createObjectNode();
            req.put("action", "decode_webp");
            req.put("data", b64);
            req.put("size", size);
            JsonNode r = call(req.toString());
            if (r == null || !r.path("ok").asBoolean(false)) return null;
            String pngB64 = r.path("png").asText();
            if (pngB64.isBlank()) return null;
            return java.util.Base64.getDecoder().decode(pngB64);
        } catch (Exception e) {
            return null;
        }
    }

    private record PingResult(boolean ok, boolean pillow) {}

    private static PingResult probe(String exe) {
        try {
            Process p = new ProcessBuilder(exe, "-E", scriptPath.toString())
                    .redirectErrorStream(false)
                    .start();
            final StringBuilder outBuf = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) outBuf.append(line);
                } catch (Exception ignored) {
                }
            });
            reader.setDaemon(true);
            reader.start();
            try (OutputStream os = p.getOutputStream()) {
                os.write("{\"action\":\"ping\"}".getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            if (!p.waitFor(8, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new PingResult(false, false);
            }
            reader.join(1500);
            if (p.exitValue() != 0) return new PingResult(false, false);
            JsonNode n = MAPPER.readTree(outBuf.toString());
            return new PingResult(n.path("ok").asBoolean(false),
                    n.path("pillow").asBoolean(false));
        } catch (Exception e) {
            return new PingResult(false, false);
        }
    }

    /** 调用 Python 脚本，返回解析后的 JSON；失败返回 null（调用方需降级） */
    public static JsonNode call(String requestJson) {
        if (pythonExe == null || scriptPath == null) return null;
        Process p = null;
        try {
            p = new ProcessBuilder(pythonExe, "-E", scriptPath.toString()).start();

            // 关键：大请求（几百 KB）会超过管道缓冲区大小，
            // 如果先写 stdin 再读 stdout，两边都在等对方，就会死锁。
            // 所以必须在写的同时异步读 stdout。
            final Process fp = p;
            final StringBuilder outBuf = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(fp.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) outBuf.append(line);
                } catch (Exception ignored) {
                }
            }, "py-stdout-reader");
            reader.setDaemon(true);
            reader.start();

            try (OutputStream os = p.getOutputStream()) {
                os.write(requestJson.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                Log.warn("Python 调用超时，降级到 Java 实现");
                return null;
            }
            reader.join(2000);

            if (p.exitValue() != 0) {
                Log.warn("Python 退出码 " + p.exitValue() + "，降级到 Java 实现");
                return null;
            }
            String out = outBuf.toString();
            if (out.isBlank()) {
                Log.warn("Python 无输出，降级到 Java 实现");
                return null;
            }
            JsonNode n = MAPPER.readTree(out);
            if (!n.path("ok").asBoolean(false)) {
                Log.warn("Python 返回错误: " + n.path("error").asText());
                return null;
            }
            return n;
        } catch (Exception e) {
            if (p != null) p.destroyForcibly();
            Log.warn("Python 调用失败: " + e.getMessage());
            return null;
        }
    }

    // ---------------- 内部工具 ----------------

    private static String readAll(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    /** 在 PATH 里找一个可执行文件 */
    private static String which(String name) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        String[] exts = Constants.isWindows()
                ? new String[]{".exe", ".cmd", ".bat", ""} : new String[]{""};
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) continue;
            for (String ext : exts) {
                File f = new File(dir, name + ext);
                if (f.isFile() && f.canExecute()) return f.getAbsolutePath();
            }
        }
        return null;
    }

    /** 把 resources 里的 Python 脚本释放到临时目录 */
    private static Path extractScript() {
        try (InputStream in = PyBridge.class.getResourceAsStream("/py/scl_helper.py")) {
            if (in == null) return null;
            Path dir = Constants.DATA_DIR.resolve("py");
            Files.createDirectories(dir);
            Path target = dir.resolve("scl_helper.py");
            Files.write(target, in.readAllBytes());
            return target;
        } catch (Exception e) {
            return null;
        }
    }
}
