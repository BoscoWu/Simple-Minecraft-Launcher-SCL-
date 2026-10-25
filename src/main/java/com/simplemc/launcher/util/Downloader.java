package com.simplemc.launcher.util;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

public final class Downloader {

    public static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static HttpClient createClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    private static String mirror(String url) {
        String source = Config.load(AppPaths.CONFIG_FILE).getString("download_source", "bmcl");
        if ("official".equals(source)) {
            return url;
        }

        // ★ 修复：日志配置文件 (client-xxx.xml) 在 BMCLAPI 中 404，强制走官方源
        if (url.contains("client-") && url.endsWith(".xml")) {
            return url;
        }

        if (url.startsWith("https://libraries.minecraft.net/")) {
            return "https://bmclapi2.bangbang93.com/libraries/"
                    + url.substring("https://libraries.minecraft.net/".length());
        }
        if (url.startsWith("https://resources.download.minecraft.net/")) {
            return "https://bmclapi2.bangbang93.com/assets/"
                    + url.substring("https://resources.download.minecraft.net/".length());
        }
        url = url.replace("https://piston-meta.mojang.com", "https://bmclapi2.bangbang93.com");
        url = url.replace("https://piston-data.mojang.com", "https://bmclapi2.bangbang93.com");
        url = url.replace("https://launchermeta.mojang.com", "https://bmclapi2.bangbang93.com");
        return url;
    }

    public static String getString(String url) throws IOException, InterruptedException {
        url = mirror(url);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", AppPaths.USER_AGENT)
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<String> resp = createClient().send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new IOException("HTTP " + resp.statusCode() + " for " + url);
        }
        return resp.body();
    }

    public static JsonNode getJson(String url) throws IOException, InterruptedException {
        return JsonUtil.parse(getString(url));
    }

    public static void download(String url, Path dest, BiConsumer<Long, Long> progress) throws Exception {
        download(url, dest, null, progress, new AtomicBoolean(false));
    }

    public static void download(String url, Path dest, String sha1, BiConsumer<Long, Long> progress) throws Exception {
        download(url, dest, sha1, progress, new AtomicBoolean(false));
    }

    public static void download(String url, Path dest, String sha1, BiConsumer<Long, Long> progress, AtomicBoolean cancelFlag) throws Exception {
        url = mirror(url);
        Files.createDirectories(dest.getParent());

        if (sha1 != null && Files.exists(dest)) {
            try {
                if (sha1(dest).equalsIgnoreCase(sha1)) {
                    return;
                }
            } catch (Exception ignored) {
            }
        }

        Path part = dest.resolveSibling(dest.getFileName() + ".part");
        int maxRetries = 5;
        Exception lastError = null;
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            if (cancelFlag.get()) {
                throw new InterruptedException("下载已取消");
            }
            try {
                long existing = Files.exists(part) ? Files.size(part) : 0;
                downloadOnce(url, part, existing, progress, cancelFlag);

                if (sha1 != null) {
                    String actual = sha1(part);
                    if (!actual.equalsIgnoreCase(sha1)) {
                        throw new IOException("SHA1 校验失败: 期望 " + sha1 + " 实际 " + actual);
                    }
                }
                Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (InterruptedException e) {
                Files.deleteIfExists(part);
                throw e;
            } catch (Exception e) {
                lastError = e;
                if (attempt < maxRetries - 1) {
                    try {
                        Thread.sleep(1000L + attempt * 2000L);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }
        throw new IOException("下载失败（已重试 " + maxRetries + " 次）: " + url, lastError);
    }

    private static void downloadOnce(String url, Path part, long existing,
                                     BiConsumer<Long, Long> progress, AtomicBoolean cancelFlag) throws Exception {
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", AppPaths.USER_AGENT)
                .timeout(Duration.ofSeconds(120))
                .GET();
        if (existing > 0) {
            rb.header("Range", "bytes=" + existing + "-");
        }
        HttpResponse<InputStream> resp = createClient().send(rb.build(), HttpResponse.BodyHandlers.ofInputStream());
        int status = resp.statusCode();
        if (status != 200 && status != 206) {
            throw new IOException("HTTP " + status + " for " + url);
        }
        boolean append = existing > 0 && status == 206;
        long start = append ? existing : 0;
        long total = resp.headers().firstValueAsLong("Content-Length").orElse(0L);
        if (append) {
            total += start;
        }
        long downloaded = start;
        try (InputStream in = resp.body();
             OutputStream out = new FileOutputStream(part.toFile(), append)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (cancelFlag.get()) {
                    throw new InterruptedException("下载已取消");
                }
                out.write(buf, 0, n);
                downloaded += n;
                if (progress != null) {
                    progress.accept(downloaded, total);
                }
            }
        }
        if (progress != null) {
            progress.accept(downloaded, total > 0 ? total : downloaded);
        }
    }

    public static String sha1(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private Downloader() {
    }
}