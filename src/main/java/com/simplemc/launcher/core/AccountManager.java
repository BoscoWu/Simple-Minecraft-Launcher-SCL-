package com.simplemc.launcher.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simplemc.launcher.util.Constants;
import com.simplemc.launcher.util.Http;
import com.simplemc.launcher.util.Log;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.UUID;

/**
 * 账户：离线账户 + Microsoft 正版登录（设备代码流）。
 */
public final class AccountManager {

    /** 登录后的账户信息 */
    public static final class Account {
        public String username = "";
        public String uuid = "";
        public String accessToken = "";
        public String kind = "离线";      // 离线 / 正版
        public String refreshToken = "";
        public long expiresAt = 0;

        public boolean isPremium() {
            return "正版".equals(kind) && accessToken != null && !accessToken.isBlank();
        }

        public boolean isValid() {
            if (!isPremium()) return !username.isBlank();
            return System.currentTimeMillis() < expiresAt - 60_000;
        }
    }

    /** 设备码登录的中间状态 */
    public record DeviceCode(String userCode, String deviceCode,
                             String verificationUri, int expiresIn, int interval) {}

    private AccountManager() {}

    // ---------------- 离线账户 ----------------

    /** 由用户名生成稳定的离线 UUID（与官方离线模式一致） */
    public static Account offline(String username) {
        Account a = new Account();
        a.username = username == null || username.isBlank() ? "Steve" : username.trim();
        a.kind = "离线";
        a.uuid = offlineUuid(a.username);
        a.accessToken = "0";
        return a;
    }

    /** 生成离线 UUID（基于 "OfflinePlayer:<name>" 的 MD5，设置 version/variant 位） */
    public static String offlineUuid(String username) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(("OfflinePlayer:" + username)
                    .getBytes(StandardCharsets.UTF_8));
            digest[6] = (byte) ((digest[6] & 0x0f) | 0x30);   // version 3
            digest[8] = (byte) ((digest[8] & 0x3f) | 0x80);   // variant
            UUID u = uuidFromBytes(digest);
            return u.toString().replace("-", "");
        } catch (Exception e) {
            return UUID.nameUUIDFromBytes(username.getBytes(StandardCharsets.UTF_8))
                    .toString().replace("-", "");
        }
    }

    private static UUID uuidFromBytes(byte[] b) {
        long msb = 0, lsb = 0;
        for (int i = 0; i < 8; i++) msb = (msb << 8) | (b[i] & 0xff);
        for (int i = 8; i < 16; i++) lsb = (lsb << 8) | (b[i] & 0xff);
        return new UUID(msb, lsb);
    }

    // ---------------- Microsoft 登录（设备代码流） ----------------

    /**
     * 第一步：申请设备码。用户需要在浏览器打开 verificationUri 并输入 userCode。
     */
    public static DeviceCode requestDeviceCode() throws Exception {
        String body = Http.MAPPER.writeValueAsString(java.util.Map.of(
                "client_id", Constants.MS_CLIENT_ID,
                "scope", "XboxLive.signin offline_access"
        ));
        HttpResponse<String> resp = postForm(
                "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode",
                body);
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("申请设备码失败: " + resp.body());
        }
        JsonNode j = Http.MAPPER.readTree(resp.body());
        return new DeviceCode(
                j.path("user_code").asText(),
                j.path("device_code").asText(),
                j.path("verification_uri").asText(),
                j.path("expires_in").asInt(900),
                j.path("interval").asInt(5));
    }

    /**
     * 第二步：轮询获取令牌，然后换取 Xbox → Minecraft 令牌。
     *
     * <p>此方法会阻塞直到成功、超时或用户取消。</p>
     */
    public static Account loginWithDeviceCode(DeviceCode dc,
                                              java.util.function.BooleanSupplier cancelled)
            throws Exception {
        long deadline = System.currentTimeMillis() + dc.expiresIn() * 1000L;
        String msAccessToken = null;

        while (System.currentTimeMillis() < deadline) {
            if (cancelled != null && cancelled.getAsBoolean()) {
                throw new IllegalStateException("已取消登录");
            }
            Thread.sleep(dc.interval() * 1000L);
            String body = Http.MAPPER.writeValueAsString(java.util.Map.of(
                    "client_id", Constants.MS_CLIENT_ID,
                    "grant_type", "urn:ietf:params:oauth:grant-type:device_code",
                    "device_code", dc.deviceCode()
            ));
            HttpResponse<String> resp = postForm(
                    "https://login.microsoftonline.com/consumers/oauth2/v2.0/token", body);
            JsonNode j = Http.MAPPER.readTree(resp.body());
            String error = j.path("error").asText("");
            if ("authorization_pending".equals(error)) {
                continue;
            }
            if ("slow_down".equals(error)) {
                Thread.sleep(2000);
                continue;
            }
            if (!error.isBlank()) {
                throw new IllegalStateException("登录失败: " + error
                        + " " + j.path("error_description").asText(""));
            }
            msAccessToken = j.path("access_token").asText("");
            String refresh = j.path("refresh_token").asText("");
            if (msAccessToken.isBlank()) {
                throw new IllegalStateException("未获得 access_token");
            }
            return completeMicrosoftLogin(msAccessToken, refresh);
        }
        throw new IllegalStateException("登录超时，请重试");
    }

    /** Xbox Live → XSTS → Minecraft */
    private static Account completeMicrosoftLogin(String msToken, String refresh)
            throws Exception {
        // 1. Xbox Live 认证
        String xblBody = Http.MAPPER.writeValueAsString(java.util.Map.of(
                "Properties", java.util.Map.of(
                        "AuthMethod", "RPS",
                        "SiteName", "user.auth.xboxlive.com",
                        "RpsTicket", "d=" + msToken),
                "RelyingParty", "http://auth.xboxlive.com",
                "TokenType", "JWT"
        ));
        JsonNode xbl = postJson("https://user.auth.xboxlive.com/user/authenticate", xblBody);
        String xblToken = xbl.path("Token").asText("");
        String uhs = xbl.path("DisplayClaims").path("xui").path(0)
                .path("uhs").asText("");
        if (xblToken.isBlank() || uhs.isBlank()) {
            throw new IllegalStateException("Xbox Live 认证失败");
        }

        // 2. XSTS 授权
        String xstsBody = Http.MAPPER.writeValueAsString(java.util.Map.of(
                "Properties", java.util.Map.of(
                        "SandboxId", "RETAIL",
                        "UserTokens", java.util.List.of(xblToken)),
                "RelyingParty", "rp://api.minecraftservices.com/",
                "TokenType", "JWT"
        ));
        JsonNode xsts = postJson("https://xsts.auth.xboxlive.com/xsts/authorize", xstsBody);
        String xstsToken = xsts.path("Token").asText("");
        if (xstsToken.isBlank()) {
            String xerr = xsts.path("XErr").asText("");
            String hint = switch (xerr) {
                case "2148916233" -> "该 Microsoft 账户尚未创建 Xbox 档案";
                case "2148916238" -> "该账户是未成年人账户，需要家长同意";
                default -> "XSTS 授权失败 (XErr=" + xerr + ")";
            };
            throw new IllegalStateException(hint);
        }

        // 3. 换取 Minecraft 令牌
        String mcBody = Http.MAPPER.writeValueAsString(java.util.Map.of(
                "identityToken", "XBL3.0 x=" + uhs + ";" + xstsToken
        ));
        JsonNode mc = postJson(
                "https://api.minecraftservices.com/authentication/login_with_xbox", mcBody);
        String mcToken = mc.path("access_token").asText("");
        if (mcToken.isBlank()) {
            throw new IllegalStateException("获取 Minecraft 令牌失败");
        }
        long expiresIn = mc.path("expires_in").asLong(86400);

        // 4. 获取档案
        JsonNode profile = getJson(
                "https://api.minecraftservices.com/minecraft/profile", mcToken);
        String name = profile.path("name").asText("");
        String uuid = profile.path("id").asText("");
        if (name.isBlank()) {
            throw new IllegalStateException("该账户未拥有 Minecraft Java 版");
        }

        Account a = new Account();
        a.username = name;
        a.uuid = uuid;
        a.accessToken = mcToken;
        a.kind = "正版";
        a.refreshToken = refresh;
        a.expiresAt = System.currentTimeMillis() + expiresIn * 1000L;
        Log.info("正版登录成功: " + name);
        return a;
    }

    // ---------------- HTTP 辅助 ----------------

    private static HttpResponse<String> postForm(String url, String jsonBody)
            throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", Constants.USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build()
                .send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode postJson(String url, String body) throws Exception {
        HttpResponse<String> r = postForm(url, body);
        if (r.body() == null || r.body().isBlank()) {
            throw new IllegalStateException("空响应: HTTP " + r.statusCode());
        }
        return Http.MAPPER.readTree(r.body());
    }

    private static JsonNode getJson(String url, String bearer) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + bearer)
                .header("Accept", "application/json")
                .header("User-Agent", Constants.USER_AGENT)
                .GET().build();
        HttpResponse<String> r = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build()
                .send(req, HttpResponse.BodyHandlers.ofString());
        return Http.MAPPER.readTree(r.body());
    }
}
