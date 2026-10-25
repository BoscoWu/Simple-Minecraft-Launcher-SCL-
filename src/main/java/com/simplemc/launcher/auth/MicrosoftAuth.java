package com.simplemc.launcher.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simplemc.launcher.util.AppPaths;
import com.simplemc.launcher.util.Downloader;
import com.simplemc.launcher.util.JsonUtil;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 微软账号登录（设备代码流 + Xbox Live/XSTS/Minecraft 认证 + 令牌刷新）。
 */
public final class MicrosoftAuth {

    /** 登录结果。 */
    public record Account(String username, String uuid, String accessToken, String refreshToken) {}

    private static final String TENANT = "consumers";
    private static final String DEVICE_CODE_URL =
            "https://login.microsoftonline.com/" + TENANT + "/oauth2/v2.0/devicecode";
    private static final String TOKEN_URL =
            "https://login.microsoftonline.com/" + TENANT + "/oauth2/v2.0/token";
    private static final String XBL_AUTH_URL = "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XSTS_URL = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String MC_AUTH_URL = "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile";

    /**
     * 设备代码流登录（阻塞，直到完成或失败）。
     *
     * @param log 进度日志回调
     * @return 账号信息；失败返回 null
     */
    public static Account deviceLogin(Consumer<String> log) throws Exception {
        JsonNode dev = postForm(DEVICE_CODE_URL, Map.of(
                "client_id", AppPaths.CLIENT_ID,
                "scope", "XboxLive.signin offline_access"));
        String userCode = dev.get("user_code").asText();
        String deviceCode = dev.get("device_code").asText();
        String verifyUrl = dev.has("verification_uri")
                ? dev.get("verification_uri").asText() : "https://www.microsoft.com/link";
        int expiresIn = dev.has("expires_in") ? dev.get("expires_in").asInt() : 900;
        int interval = dev.has("interval") ? dev.get("interval").asInt() : 5;

        log.accept("请打开浏览器访问: " + verifyUrl);
        log.accept("输入设备代码: " + userCode);

        long deadline = System.currentTimeMillis() + expiresIn * 1000L;
        String accessToken = null;
        String refreshToken = null;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(interval * 1000L);
            JsonNode t = postForm(TOKEN_URL, Map.of(
                    "grant_type", "urn:ietf:params:oauth:grant-type:device_code",
                    "client_id", AppPaths.CLIENT_ID,
                    "device_code", deviceCode));
            if (t.has("access_token")) {
                accessToken = t.get("access_token").asText();
                refreshToken = t.has("refresh_token") ? t.get("refresh_token").asText() : null;
                break;
            }
            String err = t.has("error") ? t.get("error").asText() : "";
            switch (err) {
                case "authorization_pending" -> log.accept("等待完成登录...");
                case "slow_down" -> interval += 5;
                case "authorization_declined" -> {
                    log.accept("登录被用户取消");
                    return null;
                }
                case "expired_token" -> {
                    log.accept("设备代码已过期，请重新登录");
                    return null;
                }
                default -> {
                    log.accept("微软登录失败: " + t);
                    return null;
                }
            }
        }
        if (accessToken == null) {
            log.accept("登录超时");
            return null;
        }
        Account account = completeLogin(accessToken, refreshToken);
        log.accept("登录成功！欢迎 " + account.username());
        return account;
    }

    /**
     * 获取设备代码流的第一步信息（不阻塞，返回给 UI 展示）。
     */
    public record DeviceCode(String userCode, String verifyUrl, String deviceCode,
                             int expiresIn) {}

    public static DeviceCode requestDeviceCode() throws Exception {
        JsonNode dev = postForm(DEVICE_CODE_URL, Map.of(
                "client_id", AppPaths.CLIENT_ID,
                "scope", "XboxLive.signin offline_access"));
        return new DeviceCode(
                dev.get("user_code").asText(),
                dev.has("verification_uri") ? dev.get("verification_uri").asText()
                        : "https://www.microsoft.com/link",
                dev.get("device_code").asText(),
                dev.has("expires_in") ? dev.get("expires_in").asInt() : 900);
    }

    /**
     * 用设备代码轮询换取账号（阻塞，直到成功/失败/超时）。
     */
    public static Account pollDeviceCode(DeviceCode dc, Consumer<String> log) throws Exception {
        long deadline = System.currentTimeMillis() + dc.expiresIn() * 1000L;
        int interval = 5;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(interval * 1000L);
            JsonNode t = postForm(TOKEN_URL, Map.of(
                    "grant_type", "urn:ietf:params:oauth:grant-type:device_code",
                    "client_id", AppPaths.CLIENT_ID,
                    "device_code", dc.deviceCode()));
            if (t.has("access_token")) {
                String accessToken = t.get("access_token").asText();
                String refreshToken = t.has("refresh_token") ? t.get("refresh_token").asText() : null;
                Account account = completeLogin(accessToken, refreshToken);
                log.accept("登录成功！欢迎 " + account.username());
                return account;
            }
            String err = t.has("error") ? t.get("error").asText() : "";
            switch (err) {
                case "authorization_pending" -> log.accept("等待完成登录...");
                case "slow_down" -> interval += 5;
                case "authorization_declined" -> {
                    log.accept("登录被用户取消");
                    return null;
                }
                case "expired_token" -> {
                    log.accept("设备代码已过期");
                    return null;
                }
                default -> {
                    log.accept("微软登录失败: " + t);
                    return null;
                }
            }
        }
        log.accept("登录超时");
        return null;
    }

    /**
     * 用刷新令牌换取新的账号信息。
     */
    public static Account refresh(String refreshToken) throws Exception {
        JsonNode t = postForm(TOKEN_URL, Map.of(
                "grant_type", "refresh_token",
                "client_id", AppPaths.CLIENT_ID,
                "scope", "XboxLive.signin offline_access",
                "refresh_token", refreshToken));
        if (t.has("error")) {
            throw new IOException("刷新令牌失败: " + t);
        }
        String accessToken = t.get("access_token").asText();
        String newRefresh = t.has("refresh_token") ? t.get("refresh_token").asText() : refreshToken;
        return completeLogin(accessToken, newRefresh);
    }

    /**
     * 完成 XBL -> XSTS -> Minecraft -> Profile 的完整认证。
     */
    private static Account completeLogin(String msAccessToken, String msRefreshToken) throws Exception {
        ObjectNode xblBody = JsonUtil.newObject();
        ObjectNode props = xblBody.putObject("Properties");
        props.put("AuthMethod", "RPS");
        props.put("SiteName", "user.auth.xboxlive.com");
        props.put("RpsTicket", "d=" + msAccessToken);
        xblBody.put("RelyingParty", "http://auth.xboxlive.com");
        xblBody.put("TokenType", "JWT");
        JsonNode xbl = postJson(XBL_AUTH_URL, xblBody);
        String xblToken = xbl.get("Token").asText();
        String uhs = xbl.get("DisplayClaims").get("xui").get(0).get("uhs").asText();

        ObjectNode xstsBody = JsonUtil.newObject();
        ObjectNode xstsProps = xstsBody.putObject("Properties");
        xstsProps.put("SandboxId", "RETAIL");
        xstsProps.putArray("UserTokens").add(xblToken);
        xstsBody.put("RelyingParty", "rp://api.minecraftservices.com/");
        xstsBody.put("TokenType", "JWT");
        JsonNode xsts = postJson(XSTS_URL, xstsBody);
        if (!xsts.has("Token")) {
            long xerr = xsts.has("XErr") ? xsts.get("XErr").asLong() : -1;
            String msg;
            if (xerr == 2148916233L) {
                msg = "此微软账号没有 Xbox 账号，请先创建";
            } else if (xerr == 2148916238L) {
                msg = "此账号是儿童账号，需要家长授权";
            } else {
                msg = "XSTS 验证失败: " + xsts;
            }
            throw new IOException(msg);
        }
        String xstsToken = xsts.get("Token").asText();

        ObjectNode mcBody = JsonUtil.newObject();
        mcBody.put("identityToken", "XBL3.0 x=" + uhs + ";" + xstsToken);
        JsonNode mc = postJson(MC_AUTH_URL, mcBody);
        String mcToken = mc.get("access_token").asText();

        JsonNode profile = getJsonWithBearer(PROFILE_URL, mcToken);
        String name = profile.get("name").asText();
        String uuid = profile.get("id").asText();

        return new Account(name, uuid, mcToken, msRefreshToken);
    }

    private static JsonNode postForm(String url, Map<String, String> params)
            throws IOException, InterruptedException {
        String body = params.entrySet().stream()
                .map(e -> urlEncode(e.getKey()) + "=" + urlEncode(e.getValue()))
                .reduce((a, b) -> a + "&" + b).orElse("");
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = Downloader.CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        return JsonUtil.parse(resp.body());
    }

    private static JsonNode postJson(String url, JsonNode json)
            throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(json.toString()))
                .build();
        HttpResponse<String> resp = Downloader.CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        return JsonUtil.parse(resp.body());
    }

    private static JsonNode getJsonWithBearer(String url, String bearer)
            throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + bearer)
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> resp = Downloader.CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("获取 Minecraft 档案失败: HTTP " + resp.statusCode());
        }
        return JsonUtil.parse(resp.body());
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private MicrosoftAuth() {
    }
}
