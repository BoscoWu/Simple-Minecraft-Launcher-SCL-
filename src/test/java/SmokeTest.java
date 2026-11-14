import com.simplemc.launcher.core.VersionManager;
import com.simplemc.launcher.core.JavaManager;
import com.simplemc.launcher.core.AccountManager;
import com.simplemc.launcher.core.ServerManager;
import com.simplemc.launcher.core.Modrinth;
import com.simplemc.launcher.core.UpdateManager;
import com.simplemc.launcher.util.Constants;

import java.util.List;

/** 核心逻辑冒烟测试 */
public class SmokeTest {
    public static void main(String[] args) {
        System.out.println("数据目录: " + Constants.DATA_DIR);
        int pass = 0, fail = 0;

        // 1. 版本列表
        try {
            VersionManager vm = new VersionManager(null);
            List<VersionManager.VersionInfo> all = vm.listAllVersions();
            String latest = vm.latestRelease();
            System.out.println("[1] 版本列表: " + all.size() + " 个, 最新正式版 = " + latest);
            if (all.size() > 100 && !latest.isBlank()) { pass++; System.out.println("    PASS"); }
            else { fail++; System.out.println("    FAIL"); }
        } catch (Exception e) {
            fail++; System.out.println("[1] 版本列表 FAIL: " + e.getMessage());
        }

        // 2. Java 检测
        try {
            List<JavaManager.JavaInfo> javas = JavaManager.detectAll();
            System.out.println("[2] 检测到 Java: " + javas.size() + " 个");
            javas.stream().limit(5).forEach(j -> System.out.println("    " + j));
            if (!javas.isEmpty()) { pass++; System.out.println("    PASS"); }
            else { fail++; System.out.println("    FAIL"); }
        } catch (Exception e) {
            fail++; System.out.println("[2] Java 检测 FAIL: " + e.getMessage());
        }

        // 3. 版本号比较
        try {
            int c1 = UpdateManager.compareVersions("v5.0.0", "v4.1.2");
            int c2 = UpdateManager.compareVersions("v4.1.2", "v5.0.0");
            int c3 = UpdateManager.compareVersions("v5.0.0", "v5.0.0");
            System.out.println("[3] 版本比较: 5.0>4.1=" + (c1 > 0) + ", 4.1<5.0=" + (c2 < 0) + ", 相等=" + (c3 == 0));
            if (c1 > 0 && c2 < 0 && c3 == 0) { pass++; System.out.println("    PASS"); }
            else { fail++; System.out.println("    FAIL"); }
        } catch (Exception e) {
            fail++; System.out.println("[3] FAIL: " + e.getMessage());
        }

        // 4. 离线 UUID
        try {
            String u1 = AccountManager.offlineUuid("Steve");
            String u2 = AccountManager.offlineUuid("Steve");
            System.out.println("[4] 离线 UUID: " + u1 + " (稳定=" + u1.equals(u2) + ")");
            if (u1.equals(u2) && u1.length() == 32) { pass++; System.out.println("    PASS"); }
            else { fail++; System.out.println("    FAIL"); }
        } catch (Exception e) {
            fail++; System.out.println("[4] FAIL: " + e.getMessage());
        }

        // 5. EULA
        try {
            java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("eula-test");
            boolean before = ServerManager.eulaAccepted(tmp);
            ServerManager.setEula(tmp, true);
            boolean after = ServerManager.eulaAccepted(tmp);
            ServerManager.setEula(tmp, false);
            boolean off = ServerManager.eulaAccepted(tmp);
            System.out.println("[5] EULA: 初始=" + before + ", 同意后=" + after + ", 拒绝后=" + off);
            if (!before && after && !off) { pass++; System.out.println("    PASS"); }
            else { fail++; System.out.println("    FAIL"); }
            try (var s = java.nio.file.Files.walk(tmp)) {
                s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try { java.nio.file.Files.deleteIfExists(p); } catch (Exception ignored) {}
                });
            }
        } catch (Exception e) {
            fail++; System.out.println("[5] FAIL: " + e.getMessage());
        }

        // 6. Modrinth
        try {
            List<Modrinth.Hit> hits = Modrinth.search(Modrinth.Category.MOD, "", "1.21.1", "fabric", 5);
            System.out.println("[6] Modrinth 搜索: " + hits.size() + " 条");
            hits.stream().limit(3).forEach(h -> System.out.println("    " + h.title()
                    + " (" + Modrinth.formatDownloads(h.downloads()) + " 下载)"));
            if (!hits.isEmpty()) { pass++; System.out.println("    PASS"); }
            else { fail++; System.out.println("    FAIL"); }
        } catch (Exception e) {
            fail++; System.out.println("[6] Modrinth FAIL: " + e.getMessage());
        }

        // 7. 更新检查
        try {
            UpdateManager.CheckResult r = UpdateManager.check();
            System.out.println("[7] 更新检查: " + r.message());
            if (r.message() != null && !r.message().isBlank()) { pass++; System.out.println("    PASS"); }
            else { fail++; System.out.println("    FAIL"); }
        } catch (Exception e) {
            fail++; System.out.println("[7] FAIL: " + e.getMessage());
        }

        // 8. Fabric 服务端（版本校验）
        try {
            ServerManager sm = new ServerManager(Constants.SERVER_DIR);
            // 用反射调用私有方法验证逻辑
            var m = ServerManager.class.getDeclaredMethod("fabricServerInfo", String.class);
            m.setAccessible(true);
            String[] info = (String[]) m.invoke(sm, "1.21.1");
            System.out.println("[8] Fabric 服务端: " + info[1]);
            System.out.println("    URL: " + info[0]);
            boolean ok = info[0].contains("1.21.1") && info[0].matches(".*/\\d+\\.\\d+\\.\\d+/server/jar");
            if (ok) { pass++; System.out.println("    PASS"); }
            else { fail++; System.out.println("    FAIL (URL 结构不对)"); }
        } catch (Exception e) {
            fail++; System.out.println("[8] FAIL: " + e.getMessage());
        }

        System.out.println("\n========== 结果: " + pass + " 通过, " + fail + " 失败 ==========");
        System.exit(fail == 0 ? 0 : 1);
    }
}
