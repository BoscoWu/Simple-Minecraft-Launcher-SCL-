import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplemc.launcher.util.Constants;
import com.simplemc.launcher.util.PyBridge;

import java.net.URI;
import java.net.http.*;
import java.util.*;

/** 验证 Python 桥：检测、调用、以及无 Python 时的降级 */
public class PyBridgeTest {

    static int pass = 0, fail = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "  " + detail); }
    }

    public static void main(String[] args) throws Exception {
        Constants.ensureDirectories();

        System.out.println("=== 1. Python 检测 ===");
        boolean avail = PyBridge.isAvailable();
        System.out.println("  Python 可用: " + avail);
        if (avail) {
            System.out.println("  版本: " + PyBridge.pythonVersion());
        }
        check("检测不抛异常", true, "");

        if (!avail) {
            System.out.println("  跳过后续调用测试（本机无 Python 属于正常降级场景）");
            System.out.println();
            System.out.println("========== " + pass + " 通过, " + fail + " 失败 ==========");
            return;
        }

        System.out.println();
        System.out.println("=== 2. ping 调用 ===");
        JsonNode ping = PyBridge.call("{\"action\":\"ping\"}");
        check("ping 成功", ping != null && ping.path("ok").asBoolean(false), "");
        if (ping != null) {
            System.out.println("  impl=" + ping.path("impl").asText()
                    + " version=" + ping.path("version").asText());
        }

        System.out.println();
        System.out.println("=== 3. 分类真实版本清单 ===");
        HttpClient hc = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.ALWAYS).build();
        HttpRequest req = HttpRequest.newBuilder(
                URI.create("https://bmclapi2.bangbang93.com/mc/game/version_manifest_v2.json"))
                .header("User-Agent", "SimpleMCLauncher/5.1.0")
                .timeout(java.time.Duration.ofSeconds(40))
                .GET().build();
        HttpResponse<byte[]> resp = hc.send(req, HttpResponse.BodyHandlers.ofByteArray());

        ObjectMapper M = new ObjectMapper();
        JsonNode manifest = M.readTree(resp.body());
        JsonNode versions = manifest.path("versions");
        System.out.println("  清单条数: " + versions.size());

        // 构造请求
        var reqNode = M.createObjectNode();
        reqNode.put("action", "classify");
        var arrNode = M.createArrayNode();
        for (JsonNode v : versions) {
            var o = M.createObjectNode();
            o.put("id", v.path("id").asText());
            o.put("type", v.path("type").asText());
            o.put("releaseTime", v.path("releaseTime").asText());
            arrNode.add(o);
        }
        reqNode.set("versions", arrNode);

        long t0 = System.nanoTime();
        JsonNode result = PyBridge.call(M.writeValueAsString(reqNode));
        long ms = (System.nanoTime() - t0) / 1_000_000;

        check("分类调用成功", result != null, "");
        if (result != null) {
            JsonNode counts = result.path("counts");
            System.out.println("  耗时 " + ms + " ms");
            System.out.println("  release=" + counts.path("release").asInt()
                    + " snapshot=" + counts.path("snapshot").asInt()
                    + " old=" + counts.path("old").asInt()
                    + " april=" + counts.path("april").asInt());

            int rel = counts.path("release").asInt();
            int snap = counts.path("snapshot").asInt();
            int old = counts.path("old").asInt();
            int april = counts.path("april").asInt();
            int sum = rel + snap + old + april;

            check("release = 103", rel == 103, String.valueOf(rel));
            check("april = 10", april == 10, String.valueOf(april));
            check("总数一致", sum == versions.size(),
                  sum + " vs " + versions.size());

            JsonNode aprilIds = result.path("groups").path("april");
            System.out.println("  愚人节版本:");
            for (JsonNode id : aprilIds) {
                System.out.println("    " + id.asText());
            }
        }

        System.out.println();
        System.out.println("=== 4. 加载器版本过滤 ===");
        var f = M.createObjectNode();
        f.put("action", "filter_loader");
        f.put("mcVersion", "1.21.1");
        f.put("loader", "neoforge");
        var varr = M.createArrayNode();
        for (String v : new String[]{"21.1.266", "21.1.100", "21.2.5", "20.1.1", "21.10.0"}) {
            varr.add(v);
        }
        f.set("versions", varr);
        JsonNode fr = PyBridge.call(M.writeValueAsString(f));
        check("过滤调用成功", fr != null, "");
        if (fr != null) {
            System.out.println("  note: " + fr.path("note").asText());
            System.out.println("  匹配: " + fr.path("versions"));
            check("NeoForge 21.1.x 匹配 2 个",
                  fr.path("versions").size() == 2,
                  String.valueOf(fr.path("versions").size()));
        }

        System.out.println();
        System.out.println("=== 5. 非法请求应优雅失败 ===");
        JsonNode bad = PyBridge.call("{\"action\":\"not_exist\"}");
        check("非法 action 返回 null（可降级）", bad == null, "");
        JsonNode broken = PyBridge.call("not valid json");
        check("畸形 JSON 返回 null（可降级）", broken == null, "");

        System.out.println();
        System.out.println("========== " + pass + " 通过, " + fail + " 失败 ==========");
        System.exit(fail == 0 ? 0 : 1);
    }
}
