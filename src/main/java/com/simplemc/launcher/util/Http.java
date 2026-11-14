package com.simplemc.launcher.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP 与 JSON 工具。
 */
public final class Http {

    private Http() {}

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private static volatile HttpClient client;

    public static HttpClient client() {
        if (client == null) {
            synchronized (Http.class) {
                if (client == null) {
                    client = HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(20))
                            .followRedirects(HttpClient.Redirect.ALWAYS)
                            .build();
                }
            }
        }
        return client;
    }

    /** 发起 GET 请求并返回响应 */
    public static HttpResponse<byte[]> get(String url, Duration timeout) throws Exception {
        return get(url, timeout, null);
    }

    public static HttpResponse<byte[]> get(String url, Duration timeout,
                                           Map<String, String> extraHeaders) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("User-Agent", Constants.USER_AGENT)
                .header("Accept", "*/*")
                .GET();
        if (extraHeaders != null) {
            extraHeaders.forEach(b::header);
        }
        return client().send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    /** 拉取 JSON */
    public static JsonNode fetchJson(String url) throws Exception {
        return fetchJson(url, Duration.ofSeconds(25), null);
    }

    public static JsonNode fetchJson(String url, Duration timeout,
                                     Map<String, String> headers) throws Exception {
        HttpResponse<byte[]> resp = get(url, timeout, headers);
        if (resp.statusCode() != 200) {
            throw new RuntimeException("HTTP " + resp.statusCode() + " for " + url);
        }
        return MAPPER.readTree(resp.body());
    }

    /** 带多镜像的 JSON 拉取：逐个尝试，全部失败抛异常 */
    public static JsonNode fetchJsonFromMirrors(String path) throws Exception {
        Exception last = null;
        for (String m : Constants.GH_MIRRORS) {
            try {
                JsonNode node = fetchJson(m + path, Duration.ofSeconds(12), null);
                return node;
            } catch (Exception e) {
                last = e;
                Log.warn("镜像失败 " + m + ": " + e.getMessage());
            }
        }
        // 直连兜底
        try {
            return fetchJson(path, Duration.ofSeconds(12), null);
        } catch (Exception e) {
            last = e;
        }
        throw last != null ? last : new RuntimeException("所有镜像均失败");
    }

    /** 判断 URL 是否安全（黑名单 + 白名单校验） */
    public static boolean isSafeUrl(String url) {
        if (url == null || url.isBlank()) return false;
        String low = url.toLowerCase();
        for (String bad : Constants.MIRROR_BLACKLIST) {
            if (low.contains(bad)) return false;
        }
        return low.contains("github.com")
                || low.contains("gh-proxy.com")
                || low.contains("ghproxy.net")
                || low.contains("ghfast.top");
    }

    public static Map<String, String> jsonHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json");
        h.put("Accept", "application/json");
        return h;
    }
}
