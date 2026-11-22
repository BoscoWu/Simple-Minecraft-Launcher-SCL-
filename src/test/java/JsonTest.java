import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;

/** 验证轻量 JSON 库：解析、写回、往返一致性 */
public class JsonTest {

    static int pass = 0, fail = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  OK   " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name + "  " + detail);
        }
    }

    public static void main(String[] args) throws Exception {
        ObjectMapper M = new ObjectMapper();

        System.out.println("=== 1. 基础解析 ===");
        JsonNode n = M.readTree("{\"a\":1,\"b\":\"x\",\"c\":true,\"d\":null,\"e\":[1,2,3]}");
        check("int", n.path("a").asInt() == 1, n.path("a").asText());
        check("string", "x".equals(n.path("b").asText()), n.path("b").asText());
        check("bool", n.path("c").asBoolean(), "");
        check("null", n.path("d").isNull(), "");
        check("array size", n.path("e").size() == 3, String.valueOf(n.path("e").size()));
        check("missing", n.path("zzz").isMissingNode(), "");
        check("has", n.has("a") && !n.has("zzz"), "");

        System.out.println();
        System.out.println("=== 2. 嵌套与链式 path ===");
        JsonNode deep = M.readTree("{\"a\":{\"b\":{\"c\":\"deep\"}}}");
        check("3层嵌套", "deep".equals(deep.path("a").path("b").path("c").asText()),
              deep.path("a").path("b").path("c").asText());

        System.out.println();
        System.out.println("=== 3. 序列化往返 ===");
        String out = M.writeValueAsString(n);
        JsonNode n2 = M.readTree(out);
        check("往返 a", n2.path("a").asInt() == 1, "");
        check("往返 b", "x".equals(n2.path("b").asText()), "");
        check("往返 e", n2.path("e").size() == 3, "");

        System.out.println();
        System.out.println("=== 4. pretty print ===");
        String pretty = M.writerWithDefaultPrettyPrinter().writeValueAsString(n);
        check("含换行", pretty.contains("\n"), "");
        JsonNode n3 = M.readTree(pretty);
        check("pretty 可回读", n3.path("a").asInt() == 1, "");

        System.out.println();
        System.out.println("=== 5. ObjectNode 构建 ===");
        ObjectNode obj = M.createObjectNode();
        obj.put("name", "test").put("ver", 3).put("ok", true);
        obj.putObject("sub").put("k", "v");
        obj.putArray("arr").add(1).add(2);
        String s = M.writeValueAsString(obj);
        JsonNode back = M.readTree(s);
        check("put 字符串", "test".equals(back.path("name").asText()), "");
        check("put 数字", back.path("ver").asInt() == 3, "");
        check("putObject", "v".equals(back.path("sub").path("k").asText()), "");
        check("putArray", back.path("arr").size() == 2, "");

        System.out.println();
        System.out.println("=== 6. deepCopy ===");
        JsonNode cp = back.deepCopy();
        check("副本内容一致", cp.path("name").asText().equals("test"), "");

        System.out.println();
        System.out.println("=== 7. 特殊字符转义 ===");
        ObjectNode esc = M.createObjectNode();
        esc.put("text", "含\"引号\"和\n换行\t制表");
        String escStr = M.writeValueAsString(esc);
        JsonNode escBack = M.readTree(escStr);
        check("转义往返", "含\"引号\"和\n换行\t制表".equals(escBack.path("text").asText()),
              escBack.path("text").asText());

        System.out.println();
        System.out.println("=== 8. 中文 ===");
        ObjectNode cn = M.createObjectNode();
        cn.put("msg", "极简 MC 启动器");
        check("中文往返",
              "极简 MC 启动器".equals(M.readTree(M.writeValueAsString(cn)).path("msg").asText()),
              "");

        System.out.println();
        System.out.println("=== 9. 浮点数 ===");
        JsonNode f = M.readTree("{\"x\":1.5,\"y\":2.0,\"z\":1e3}");
        check("1.5", Math.abs(f.path("x").asDouble() - 1.5) < 1e-9, f.path("x").asText());
        check("2.0 整数化", "2".equals(f.path("y").asText()), f.path("y").asText());
        check("1e3", Math.abs(f.path("z").asDouble() - 1000) < 1e-9, f.path("z").asText());

        System.out.println();
        System.out.println("=== 10. 真实 API 数据 ===");
        try {
            HttpClient hc = HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(20))
                    .followRedirects(HttpClient.Redirect.ALWAYS).build();
            HttpRequest req = HttpRequest.newBuilder(
                    URI.create("https://bmclapi2.bangbang93.com/mc/game/version_manifest_v2.json"))
                    .header("User-Agent", "SimpleMCLauncher/5.1.0")
                    .timeout(java.time.Duration.ofSeconds(30))
                    .GET().build();
            HttpResponse<byte[]> resp = hc.send(req, HttpResponse.BodyHandlers.ofByteArray());
            JsonNode manifest = M.readTree(resp.body());
            int count = manifest.path("versions").size();
            check("版本清单条数 > 900", count > 900, "实际 " + count);

            JsonNode first = manifest.path("versions").path(0);
            check("能取到 id", !first.path("id").asText().isBlank(),
                  first.path("id").asText());
            check("能取到 type", !first.path("type").asText().isBlank(),
                  first.path("type").asText());

            // 遍历所有版本的字段（模拟愚人节版本分组逻辑）
            int aprilCount = 0;
            int snapshotCount = 0;
            for (JsonNode v : manifest.path("versions")) {
                String t = v.path("type").asText();
                String tm = v.path("releaseTime").asText();
                if (!"snapshot".equals(t) || tm.isBlank()) continue;
                snapshotCount++;
                try {
                    var local = java.time.OffsetDateTime.parse(tm)
                            .atZoneSameInstant(java.time.ZoneId.systemDefault());
                    if (local.getMonthValue() == 4 && local.getDayOfMonth() == 1) {
                        aprilCount++;
                        System.out.println("       愚人节: " + v.path("id").asText()
                                + "  " + local.toLocalDate());
                    }
                } catch (Exception ignored) {
                }
            }
            System.out.println("       snapshot 总数: " + snapshotCount
                    + ", 其中 4 月 1 日: " + aprilCount);
            check("愚人节版本 = 10", aprilCount == 10, "实际 " + aprilCount);
        } catch (Exception e) {
            System.out.println("  SKIP 网络不可用: " + e.getMessage());
        }

        System.out.println();
        System.out.println("=== 11. 解析已有配置文件 ===");
        Path cfg = Paths.get("pyLauncher", "config.json");
        if (Files.isRegularFile(cfg)) {
            byte[] data = Files.readAllBytes(cfg);
            long t0 = System.nanoTime();
            JsonNode c = M.readTree(data);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            check("配置文件可解析", c.isObject(), "");
            System.out.println("       解析耗时 " + ms + " ms, 字段数 " + c.size());
        } else {
            System.out.println("  SKIP 无配置文件");
        }

        System.out.println();
        System.out.println("=== 12. 大文件性能 ===");
        StringBuilder sb = new StringBuilder("{\"items\":[");
        for (int i = 0; i < 5000; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"id\":").append(i)
              .append(",\"name\":\"item").append(i)
              .append("\",\"sha1\":\"abcdef").append(i).append("\"}");
        }
        sb.append("]}");
        long t0 = System.nanoTime();
        JsonNode big = M.readTree(sb.toString());
        long parseMs = (System.nanoTime() - t0) / 1_000_000;
        long t1 = System.nanoTime();
        String bigOut = M.writeValueAsString(big);
        long writeMs = (System.nanoTime() - t1) / 1_000_000;
        check("5000 项解析", big.path("items").size() == 5000,
              String.valueOf(big.path("items").size()));
        check("5000 项写回", bigOut.length() > 100000, String.valueOf(bigOut.length()));
        System.out.println("       解析 " + parseMs + " ms, 写回 " + writeMs
                + " ms, 大小 " + (sb.length() / 1024) + " KB");

        System.out.println();
        System.out.println("========== " + pass + " 通过, " + fail + " 失败 ==========");
        System.exit(fail == 0 ? 0 : 1);
    }
}
