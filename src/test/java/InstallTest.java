import com.simplemc.launcher.core.VanillaInstaller;
import com.simplemc.launcher.core.VersionManager;
import com.simplemc.launcher.util.Constants;
import com.simplemc.launcher.util.Http;

import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** 真实安装测试：安装一个原版 Minecraft */
public class InstallTest {
    public static void main(String[] args) throws Exception {
        String mcVersion = args.length > 0 ? args[0] : "1.21.1";
        System.out.println("=== 测试安装 Minecraft " + mcVersion + " ===");
        System.out.println("目标目录: " + Constants.MC_DIR);

        VersionManager vm = new VersionManager(null);
        List<VersionManager.VersionInfo> all = vm.listAllVersions();
        var target = all.stream().filter(v -> v.id().equals(mcVersion)).findFirst()
                .orElseThrow(() -> new IllegalStateException("找不到版本 " + mcVersion));

        System.out.println("版本 URL: " + target.url());

        AtomicInteger stageCount = new AtomicInteger();
        long start = System.currentTimeMillis();

        VanillaInstaller installer = new VanillaInstaller(Constants.MC_DIR);
        installer.install(mcVersion, Http.fetchJson(target.url()),
                new VanillaInstaller.Progress() {
                    private long lastPrint = 0;
                    @Override
                    public void onStage(String stage) {
                        stageCount.incrementAndGet();
                        System.out.println("  [" + stageCount.get() + "] " + stage);
                    }
                    @Override
                    public void onProgress(long cur, long total) {
                        long now = System.currentTimeMillis();
                        if (now - lastPrint > 2000) {
                            lastPrint = now;
                            double pct = total > 0 ? 100.0 * cur / total : 0;
                            System.out.printf("      进度 %.1f%% (%d/%d)%n", pct, cur, total);
                        }
                    }
                });

        long elapsed = System.currentTimeMillis() - start;
        System.out.println("\n安装耗时: " + (elapsed / 1000) + " 秒");

        // 验证产物
        var vdir = Constants.MC_DIR.resolve("versions").resolve(mcVersion);
        System.out.println("\n=== 验证安装结果 ===");
        boolean ok = true;
        var json = vdir.resolve(mcVersion + ".json");
        var jar = vdir.resolve(mcVersion + ".jar");
        System.out.println("  版本 JSON: " + (Files.isRegularFile(json) ? "OK (" + Files.size(json) + " 字节)" : "缺失"));
        System.out.println("  客户端 jar: " + (Files.isRegularFile(jar) ? "OK (" + Files.size(jar) / 1024 / 1024 + " MB)" : "缺失"));
        if (!Files.isRegularFile(json) || !Files.isRegularFile(jar)) ok = false;

        // libraries
        var libsDir = Constants.MC_DIR.resolve("libraries");
        long libCount = Files.isDirectory(libsDir)
                ? Files.walk(libsDir).filter(Files::isRegularFile).count() : 0;
        System.out.println("  依赖库: " + libCount + " 个文件");
        if (libCount < 10) ok = false;

        // assets
        var objDir = Constants.MC_DIR.resolve("assets").resolve("objects");
        long assetCount = Files.isDirectory(objDir)
                ? Files.walk(objDir).filter(Files::isRegularFile).count() : 0;
        System.out.println("  资源文件: " + assetCount + " 个");
        if (assetCount < 100) ok = false;

        System.out.println("\n结果: " + (ok ? "PASS" : "FAIL"));
        System.exit(ok ? 0 : 1);
    }
}
