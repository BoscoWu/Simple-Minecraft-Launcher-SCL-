package com.simplemc.launcher.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simplemc.launcher.util.Constants;
import com.simplemc.launcher.util.Log;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 游戏启动器：构造 JVM 参数、classpath、main class 并启动游戏进程。
 */
public final class GameLauncher {

    /** 启动配置 */
    public static final class LaunchOptions {
        public String versionId = "";
        public String javaPath = "";
        public String username = "";
        public String uuid = "";
        public String accessToken = "0";
        public int maxMemMb = 2048;
        public int minMemMb = 1024;
        public String jvmArgs = "";
        public String gameArgs = "";
        public String windowTitle = "Minecraft";
        public boolean demo = false;
    }

    private final Path mcDir;
    private Process process;

    public GameLauncher(Path mcDir) {
        this.mcDir = mcDir;
    }

    /** 启动游戏，返回进程 */
    public Process launch(LaunchOptions opt) throws Exception {
        // 0. 确保游戏语言默认是简体中文
        ensureChineseLanguage(opt.versionId);

        // 1. 合并继承链，得到最终版本 JSON
        JsonNode vjson = mergeVersionJson(opt.versionId);

        // 2. 构造 classpath
        String classpath = buildClasspath(vjson, opt.versionId);

        // 3. natives 目录
        Path nativesDir = mcDir.resolve("versions").resolve(opt.versionId).resolve("natives");
        Files.createDirectories(nativesDir);

        // 4. 变量替换表
        Path gameDir = mcDir;
        String assetsDir = mcDir.resolve("assets").toAbsolutePath().toString();
        String assetIndex = vjson.path("assetIndex").path("id").asText("legacy");
        String libDir = mcDir.resolve("libraries").toAbsolutePath().toString();
        String versionType = vjson.path("type").asText("release");

        Map<String, String> vars = new java.util.LinkedHashMap<>();
        vars.put("auth_player_name", opt.username);
        vars.put("version_name", opt.versionId);
        vars.put("game_directory", gameDir.toAbsolutePath().toString());
        vars.put("assets_root", assetsDir);
        vars.put("game_assets", assetsDir);
        vars.put("assets_index_name", assetIndex);
        vars.put("auth_uuid", opt.uuid);
        vars.put("auth_access_token", opt.accessToken);
        vars.put("auth_session", opt.accessToken);
        vars.put("user_type", "msa");
        vars.put("version_type", versionType);
        vars.put("user_properties", "{}");
        vars.put("library_directory", libDir);
        vars.put("classpath_separator", File.pathSeparator);
        vars.put("classpath", classpath);
        vars.put("launcher_name", "SimpleMCL");
        vars.put("launcher_version", Constants.VERSION);
        vars.put("natives_directory", nativesDir.toAbsolutePath().toString());
        vars.put("resolution_width", "854");
        vars.put("resolution_height", "480");
        vars.put("clientid", "");
        vars.put("auth_xuid", "");

        // 5. 拼 JVM 参数
        List<String> cmd = new ArrayList<>();
        cmd.add(opt.javaPath);

        // 内存
        cmd.add("-Xmx" + opt.maxMemMb + "m");
        cmd.add("-Xms" + opt.minMemMb + "m");

        // 官方自带 JVM 参数
        String osKey = currentOsName();
        JsonNode argsNode = vjson.path("arguments");
        if (argsNode.has("jvm")) {
            for (JsonNode a : argsNode.get("jvm")) {
                String s = resolveArg(a, vars);
                if (s == null || s.isBlank()) continue;
                // 跳过官方内存参数，用我们自己的
                if (s.startsWith("-Xmx") || s.startsWith("-Xms")) continue;
                // 官方会给 -cp ${classpath}，我们统一自己拼
                if (s.equals("-cp") || s.equals("-classpath") || s.startsWith("-cp ")) {
                    continue;
                }
                // 有的参数值里带空格（如 Fabric 的 "-DFabricMcEmu= net.minecraft..."），
                // 但 -Dname= value 这种形式必须拼成 -Dname=value，否则 JVM 会把
                // 空格后的内容当成下一个参数（甚至主类），导致 ClassNotFound。
                String trimmed = s.trim();
                if (trimmed.startsWith("-D") && trimmed.contains("= ")) {
                    trimmed = trimmed.replace("= ", "=").replace(" =", "=");
                }
                for (String part : splitArgs(trimmed)) {
                    if (part.isBlank()) continue;
                    if (part.equals("-cp") || part.equals("-classpath")) continue;
                    // 官方模板里的裸 ${classpath}（classpath 已由我们自己添加）
                    if (part.equals(classpath) || part.contains("${classpath}")) continue;
                    cmd.add(part);
                }
            }
        } else {
            // 老版本：没有 arguments，用固定参数
            cmd.add("-Djava.library.path=" + vars.get("natives_directory"));
        }

        // 统一由这里加 classpath
        cmd.add("-cp");
        cmd.add(classpath);

        // 自定义 JVM 参数
        if (opt.jvmArgs != null && !opt.jvmArgs.isBlank()) {
            for (String s : splitArgs(opt.jvmArgs)) {
                if (s.startsWith("-Xmx") || s.startsWith("-Xms")) continue;
                cmd.add(s);
            }
        }

        // 6. main class
        String mainClass = vjson.path("mainClass").asText("");
        if (mainClass.isBlank()) {
            throw new IllegalStateException("版本 " + opt.versionId + " 缺少 mainClass");
        }
        cmd.add(mainClass);

        // 7. 游戏参数
        if (argsNode.has("game")) {
            for (JsonNode a : argsNode.get("game")) {
                String s = resolveArg(a, vars);
                if (s != null) cmd.add(s);
            }
        } else {
            // 老版本固定参数
            cmd.add("--username"); cmd.add(opt.username);
            cmd.add("--version"); cmd.add(opt.versionId);
            cmd.add("--gameDir"); cmd.add(vars.get("game_directory"));
            cmd.add("--assetsDir"); cmd.add(assetsDir);
            cmd.add("--assetIndex"); cmd.add(assetIndex);
            cmd.add("--uuid"); cmd.add(opt.uuid);
            cmd.add("--accessToken"); cmd.add(opt.accessToken);
            cmd.add("--userType"); cmd.add("msa");
            cmd.add("--versionType"); cmd.add(versionType);
        }

        // 自定义游戏参数
        if (opt.gameArgs != null && !opt.gameArgs.isBlank()) {
            cmd.addAll(splitArgs(opt.gameArgs));
        }

        // 窗口标题
        if (opt.windowTitle != null && !opt.windowTitle.isBlank()) {
            int idx = cmd.indexOf("--title");
            if (idx >= 0 && idx + 1 < cmd.size()) {
                cmd.set(idx + 1, opt.windowTitle);
            }
        }

        // 8. 解压 natives（老版本需要）
        VanillaInstaller.extractNatives(vjson, nativesDir);

        // 9. 启动
        Log.info("启动命令: " + String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(gameDir.toFile());
        pb.redirectErrorStream(true);
        // 记录日志到文件（每次启动覆盖），同时供界面读取
        Path logFile = mcDir.resolve("logs").resolve("launcher-latest.log");
        Files.createDirectories(logFile.getParent());
        Files.deleteIfExists(logFile);
        pb.redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()));

        process = pb.start();
        Log.info("游戏已启动, PID=" + process.pid());
        return process;
    }

    /**
     * 确保游戏语言为简体中文。
     *
     * <p>Minecraft 首次启动时 options.txt 里的 lang 默认是 en_us，
     * 这里在启动前写入 zh_cn，让游戏界面默认就是中文。</p>
     *
     * <p>已存在 options.txt 时只补写 lang 字段，不改动用户其他设置。</p>
     */
    private void ensureChineseLanguage(String versionId) {
        try {
            Path options = mcDir.resolve("options.txt");
            String langLine = "lang:zh_cn";
            if (!Files.isRegularFile(options)) {
                // 新配置文件：写入必要的默认项
                Files.writeString(options,
                        "version:3465" + System.lineSeparator()
                        + langLine + System.lineSeparator(),
                        java.nio.charset.StandardCharsets.UTF_8);
                Log.info("已创建 options.txt 并设置语言为简体中文");
                return;
            }
            // 已有文件：检查 lang 行
            java.util.List<String> lines = new java.util.ArrayList<>(
                    Files.readAllLines(options, java.nio.charset.StandardCharsets.UTF_8));
            int langIdx = -1;
            String currentLang = null;
            for (int i = 0; i < lines.size(); i++) {
                String ln = lines.get(i);
                if (ln.startsWith("lang:")) {
                    langIdx = i;
                    currentLang = ln.substring(5).trim();
                    break;
                }
            }
            if (langLine.equals("lang:" + currentLang)) {
                return;   // 已是中文，不动
            }
            if (langIdx >= 0) {
                lines.set(langIdx, langLine);
            } else {
                lines.add(langLine);
            }
            Files.writeString(options, String.join(System.lineSeparator(), lines)
                            + System.lineSeparator(),
                    java.nio.charset.StandardCharsets.UTF_8);
            Log.info("游戏语言已设为简体中文（原: "
                    + (currentLang == null ? "未设置" : currentLang) + "）");
        } catch (Exception e) {
            Log.warn("设置游戏语言失败: " + e.getMessage());
        }
    }

    /** 递归合并 inheritsFrom 链 */
    public JsonNode mergeVersionJson(String versionId) throws Exception {
        VersionManager vm = new VersionManager(null);
        JsonNode child = vm.readVersionJson(versionId);
        return mergeRecursive(child, 0);
    }

    private JsonNode mergeRecursive(JsonNode child, int depth) throws Exception {
        if (depth > 5) return child;
        String parentId = child.path("inheritsFrom").asText("");
        if (parentId.isBlank()) {
            return child;
        }
        VersionManager vm = new VersionManager(null);
        JsonNode parent = vm.readVersionJson(parentId);
        JsonNode resolvedParent = mergeRecursive(parent, depth + 1);

        com.fasterxml.jackson.databind.node.ObjectNode merged =
                ((com.fasterxml.jackson.databind.node.ObjectNode) resolvedParent).deepCopy();

        // 子版本覆盖父版本
        var fields = child.fields();
        while (fields.hasNext()) {
            var e = fields.next();
            String key = e.getKey();
            if ("inheritsFrom".equals(key)) {
                // 保留父链关系，不要被子版本覆盖
                continue;
            }
            if ("libraries".equals(key) && e.getValue().isArray()) {
                // 合并 libraries
                var arr = merged.withArray("libraries");
                for (JsonNode lib : e.getValue()) arr.add(lib);
            } else if ("arguments".equals(key)) {
                // arguments 的 jvm/game 各自合并
                ObjectNode margs = merged.with("arguments");
                ObjectNode cargs = (ObjectNode) e.getValue();
                var it2 = cargs.fields();
                while (it2.hasNext()) {
                    var ae = it2.next();
                    if (ae.getValue().isArray() && margs.has(ae.getKey())
                            && margs.get(ae.getKey()).isArray()) {
                        var arr = (ArrayNode) margs.get(ae.getKey());
                        for (JsonNode a : ae.getValue()) arr.add(a);
                    } else {
                        margs.set(ae.getKey(), ae.getValue());
                    }
                }
            } else {
                merged.set(key, e.getValue());
            }
        }
        return merged;
    }

    /** 构造 classpath：所有可用的 libraries + client jar */
    public String buildClasspath(JsonNode vjson, String versionId) {
        Set<String> entries = new LinkedHashSet<>();
        JsonNode libs = vjson.path("libraries");
        if (libs.isArray()) {
            for (JsonNode lib : libs) {
                if (!libAllowed(lib)) continue;

                // 方式一：downloads.artifact（官方原版库）
                JsonNode artifact = lib.path("downloads").path("artifact");
                if (!artifact.isMissingNode()) {
                    String path = artifact.path("path").asText("");
                    if (!path.isBlank()) {
                        Path p = mcDir.resolve("libraries").resolve(path);
                        if (Files.isRegularFile(p)) {
                            entries.add(p.toAbsolutePath().toString());
                            continue;
                        }
                    }
                }

                // 方式二：name 是 maven 坐标（Fabric/Quilt 的库这样写）
                String name = lib.path("name").asText("");
                if (!name.isBlank()) {
                    Path p = mavenToPath(name);
                    if (p != null && Files.isRegularFile(p)) {
                        entries.add(p.toAbsolutePath().toString());
                    } else if (p != null) {
                        Log.warn("缺少库文件: " + name + " -> " + p);
                    }
                }
            }
        }
        // client jar：优先用版本自己的，没有就找父版本
        Path clientJar = mcDir.resolve("versions").resolve(versionId).resolve(versionId + ".jar");
        if (!Files.isRegularFile(clientJar)) {
            String parent = vjson.path("inheritsFrom").asText("");
            if (!parent.isBlank()) {
                Path pj = mcDir.resolve("versions").resolve(parent).resolve(parent + ".jar");
                if (Files.isRegularFile(pj)) clientJar = pj;
            }
        }
        if (Files.isRegularFile(clientJar)) {
            entries.add(clientJar.toAbsolutePath().toString());
        } else {
            Log.warn("找不到客户端 jar: " + clientJar);
        }
        return String.join(File.pathSeparator, entries);
    }

    /** 把 maven 坐标 group:artifact:version[:classifier] 转成 libraries 下的路径 */
    private Path mavenToPath(String coord) {
        try {
            String[] parts = coord.split(":");
            if (parts.length < 3) return null;
            String group = parts[0];
            String artifact = parts[1];
            String version = parts[2];
            String classifier = parts.length > 3 ? parts[3] : "";
            String fileName = artifact + "-" + version
                    + (classifier.isBlank() ? "" : "-" + classifier) + ".jar";
            Path p = mcDir.resolve("libraries");
            for (String g : group.split("\\.")) {
                p = p.resolve(g);
            }
            return p.resolve(artifact).resolve(version).resolve(fileName);
        } catch (Exception e) {
            return null;
        }
    }

    /** library 的规则是否允许当前平台（classpath 用） */
    private boolean libAllowed(JsonNode lib) {
        JsonNode rules = lib.path("rules");
        if (!rules.isArray() || rules.isEmpty()) return true;
        boolean allowed = false;
        for (JsonNode rule : rules) {
            String action = rule.path("action").asText();
            JsonNode os = rule.path("os");
            boolean matches = true;
            if (!os.isMissingNode()) {
                String name = os.path("name").asText("");
                if (!name.isBlank() && !name.equals(currentOsName())) matches = false;
            }
            JsonNode features = rule.path("features");
            if (!features.isMissingNode() && features.size() > 0) {
                matches = false;
            }
            if (matches) allowed = "allow".equals(action);
        }
        return allowed;
    }

    /** 解析一个参数节点（可能是字符串或带 rules 的对象） */
    private String resolveArg(JsonNode node, Map<String, String> vars) {
        if (node.isTextual()) {
            return substitute(node.asText(), vars);
        }
        if (node.isObject()) {
            JsonNode rules = node.path("rules");
            if (rules.isArray() && !rulesAllows(rules)) {
                return null;
            }
            JsonNode value = node.path("value");
            if (value.isTextual()) {
                return substitute(value.asText(), vars);
            }
            if (value.isArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode v : value) {
                    String s = substitute(v.asText(), vars);
                    if (!s.isBlank()) {
                        if (sb.length() > 0) sb.append(' ');
                        sb.append(s);
                    }
                }
                return sb.toString();
            }
        }
        return null;
    }

    private boolean rulesAllows(JsonNode rules) {
        boolean allowed = false;
        for (JsonNode rule : rules) {
            String action = rule.path("action").asText();
            JsonNode os = rule.path("os");
            boolean matches = true;
            if (!os.isMissingNode()) {
                String name = os.path("name").asText("");
                if (!name.isBlank() && !name.equals(currentOsName())) matches = false;
            }
            // features 规则（如 has_custom_resolution）默认不满足
            JsonNode features = rule.path("features");
            if (!features.isMissingNode() && features.size() > 0) {
                matches = false;
            }
            if (matches) allowed = "allow".equals(action);
        }
        return allowed;
    }

    private static String substitute(String s, Map<String, String> vars) {
        String out = s;
        for (var e : vars.entrySet()) {
            out = out.replace("${" + e.getKey() + "}", e.getValue());
        }
        return out;
    }

    static List<String> splitArgs(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        for (char c : raw.toCharArray()) {
            if (c == '"') {
                inQuote = !inQuote;
            } else if (Character.isWhitespace(c) && !inQuote) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private static String currentOsName() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) return "windows";
        if (os.contains("mac")) return "osx";
        return "linux";
    }

    public boolean isRunning() {
        return process != null && process.isAlive();
    }

    public void stop() {
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (Exception ignored) {
            }
        }
    }

    public Process process() {
        return process;
    }
}
