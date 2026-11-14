import com.simplemc.launcher.core.AccountManager;
import com.simplemc.launcher.core.GameLauncher;
import com.simplemc.launcher.core.JavaManager;
import com.simplemc.launcher.core.LoaderInstaller;
import com.simplemc.launcher.util.Constants;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 端到端测试：装 Fabric -> 启动游戏 */
public class LaunchTest {

    public static void main(String[] args) throws Exception {
        String mcVersion = args.length > 0 ? args[0] : "1.21.1";
        System.out.println("=== 端到端测试: " + mcVersion + " ===");

        // 1. 安装 Fabric
        LoaderInstaller li = new LoaderInstaller(Constants.MC_DIR);
        System.out.println("\n[1] 安装 Fabric " + mcVersion + " ...");
        String versionId = li.installFabricLike(LoaderInstaller.Loader.FABRIC,
                mcVersion, null, s -> System.out.println("    " + s));
        System.out.println("    版本 ID: " + versionId);

        Path vjson = Constants.MC_DIR.resolve("versions").resolve(versionId)
                .resolve(versionId + ".json");
        if (!Files.isRegularFile(vjson)) {
            System.out.println("    FAIL: 版本 JSON 未生成");
            System.exit(1);
        }
        System.out.println("    版本 JSON: OK");

        // 2. 选 Java
        System.out.println("\n[2] 选择 Java ...");
        List<JavaManager.JavaInfo> javas = JavaManager.detectAll();
        int required = JavaManager.requiredJavaMajor(mcVersion);
        JavaManager.JavaInfo chosen = JavaManager.pickBest(javas, required);
        System.out.println("    " + chosen);
        System.out.println("    该版本需要 Java " + required + ", 选用 Java " + chosen.major());
        if (chosen.major() < required) {
            System.out.println("    FAIL: Java 版本不够");
            System.exit(1);
        }

        // 3. 合并版本 JSON + classpath
        System.out.println("\n[3] 合并版本 JSON ...");
        GameLauncher gl = new GameLauncher(Constants.MC_DIR);
        var merged = gl.mergeVersionJson(versionId);
        String mainClass = merged.path("mainClass").asText("");
        System.out.println("    mainClass: " + mainClass);
        if (mainClass.isBlank()) {
            System.out.println("    FAIL: 没有 mainClass");
            System.exit(1);
        }

        String cp = gl.buildClasspath(merged, versionId);
        int entries = cp.split(java.io.File.pathSeparator).length;
        System.out.println("    classpath: " + entries + " 个条目");
        if (entries < 10) {
            System.out.println("    FAIL: classpath 条目太少");
            System.exit(1);
        }

        // 4. 实际启动
        System.out.println("\n[4] 启动游戏（最多等 25 秒）...");
        GameLauncher.LaunchOptions opt = new GameLauncher.LaunchOptions();
        opt.versionId = versionId;
        opt.javaPath = chosen.path();
        opt.username = "TestPlayer";
        opt.uuid = AccountManager.offlineUuid("TestPlayer");
        opt.accessToken = "0";
        opt.maxMemMb = 2048;
        opt.minMemMb = 1024;

        long t0 = System.currentTimeMillis();
        Process proc = gl.launch(opt);
        System.out.println("    进程 PID=" + proc.pid());

        boolean exited = proc.waitFor(25, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - t0;

        if (exited) {
            System.out.println("    游戏在 " + elapsed + "ms 后退出, exit=" + proc.exitValue());
            Path logFile = Constants.MC_DIR.resolve("logs").resolve("launcher-latest.log");
            if (Files.isRegularFile(logFile)) {
                System.out.println("    ---- 日志最后 40 行 ----");
                // 游戏日志可能是混合编码，按 UTF-8 宽松读取
                String raw = new String(Files.readAllBytes(logFile), StandardCharsets.UTF_8);
                String[] all = raw.split("\\R");
                int from = Math.max(0, all.length - 40);
                for (int i = from; i < all.length; i++) {
                    System.out.println("    " + all[i]);
                }
            }
            boolean ok = proc.exitValue() == 0;
            System.out.println("\n结果: " + (ok ? "PASS (正常退出)" : "FAIL (非零退出)"));
            System.exit(ok ? 0 : 1);
        } else {
            System.out.println("    游戏稳定运行超过 25 秒 -> 启动成功");
            System.out.println("    正在关闭...");
            proc.destroy();
            if (!proc.waitFor(5, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
            }
            System.out.println("\n结果: PASS");
            System.exit(0);
        }
    }
}
