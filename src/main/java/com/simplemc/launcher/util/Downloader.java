package com.simplemc.launcher.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 多线程分片下载器。
 *
 * <p>先把文件切成若干段，每段并发下载到独立的 .part 文件，
 * 全部完成后合并。支持 Range 的服务器会自动启用分片，
 * 不支持时退回单流下载。</p>
 */
public final class Downloader {

    private Downloader() {}

    /** 内部构造（供静态方法使用） */
    private static Downloader instance() {
        return new Downloader();
    }

    private static final int CHUNK = 256 * 1024;

    /** 默认并发连接数，可在设置里调整 */
    private static volatile int defaultConnections = 8;

    /** 设置默认并发数（1-32） */
    public static void setDefaultConnections(int n) {
        defaultConnections = Math.max(1, Math.min(32, n));
        Log.info("下载并发数已设为 " + defaultConnections);
    }

    public static int defaultConnections() {
        return defaultConnections;
    }

    /** 低于此大小不分片，避免开销大于收益 */
    private static final long MIN_MULTI_SIZE = 512 * 1024;

    /**
     * 共享的 HttpClient。
     * 每次下载都新建 client 会导致频繁 TLS 握手，
     * 在 BMCLAPI 这类限流站点上极易出现 "Remote host terminated the handshake"。
     * 复用同一个 client 可以用上连接池与 TLS 会话复用。
     */
    private static volatile HttpClient sharedClient;

    private static HttpClient client() {
        if (sharedClient == null) {
            synchronized (Downloader.class) {
                if (sharedClient == null) {
                    sharedClient = HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(20))
                            .followRedirects(HttpClient.Redirect.ALWAYS)
                            .version(HttpClient.Version.HTTP_1_1)
                            .build();
                }
            }
        }
        return sharedClient;
    }

    /** 进度回调：已下载字节 / 总字节 */
    public interface Progress {
        void onProgress(long current, long total);
    }

    /**
     * 下载文件。
     *
     * @param urls       候选地址（第一个为主，可用于镜像）
     * @param dest       目标文件
     * @param progress   进度回调（可为 null）
     * @param expectedSha1 期望的 SHA1（可为 null，不校验）
     */
    public static Path download(List<String> urls, Path dest, Progress progress,
                                String expectedSha1) throws Exception {
        if (urls == null || urls.isEmpty()) {
            throw new IllegalArgumentException("没有可用下载地址");
        }
        // 过滤黑名单
        List<String> safe = new ArrayList<>();
        for (String u : urls) {
            if (u == null || u.isBlank()) continue;
            boolean bad = false;
            for (String b : Constants.MIRROR_BLACKLIST) {
                if (u.contains(b)) { bad = true; break; }
            }
            if (!bad) safe.add(u);
        }
        if (safe.isEmpty()) {
            throw new IllegalArgumentException("下载地址全部被安全策略拒绝");
        }

        if (dest.getParent() != null) {
            Files.createDirectories(dest.getParent());
        }

        // 归一化空 SHA1：Fabric/Quilt 等库常常不带 hash 字段，
        // 传进来的可能是 null 或空字符串，此时应完全跳过校验。
        if (expectedSha1 != null && expectedSha1.isBlank()) {
            expectedSha1 = null;
        }

        // 已存在且 SHA1 匹配则直接返回
        if (expectedSha1 != null && Files.isRegularFile(dest)) {
            if (expectedSha1.equalsIgnoreCase(sha1(dest))) {
                if (progress != null) {
                    long n = Files.size(dest);
                    progress.onProgress(n, n);
                }
                return dest;
            }
        }

        Downloader d = instance();
        Exception last = null;
        for (String url : safe) {
            try {
                d.downloadOne(url, dest, progress);
                if (expectedSha1 != null) {
                    String got = sha1(dest);
                    if (!got.equalsIgnoreCase(expectedSha1)) {
                        Files.deleteIfExists(dest);
                        throw new IOException("SHA1 不匹配: 期望 " + expectedSha1 + " 实际 " + got);
                    }
                }
                return dest;
            } catch (Exception e) {
                last = e;
                Log.warn("下载失败 " + shorten(url) + ": " + describe(e));
            }
        }
        throw last != null ? last : new IOException("下载失败");
    }

    /** 把异常转成有意义的描述（getMessage() 为 null 时用类名） */
    public static String describe(Throwable e) {
        if (e == null) return "未知错误";
        String msg = e.getMessage();
        if (msg != null && !msg.isBlank()) return msg;
        // SSL / 网络类异常常常 message 为 null
        String cls = e.getClass().getSimpleName();
        Throwable cause = e.getCause();
        if (cause != null && cause != e) {
            String cm = cause.getMessage();
            if (cm != null && !cm.isBlank()) return cls + " (" + cm + ")";
            return cls + " (" + cause.getClass().getSimpleName() + ")";
        }
        return cls;
    }

    public static Path download(String url, Path dest, Progress progress) throws Exception {
        return download(List.of(url), dest, progress, null);
    }

    private void downloadOne(String url, Path dest, Progress progress) throws Exception {
        long size = -1;
        boolean supportRange = false;

        // 用 Range: bytes=0-0 探测
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(25))
                    .header("User-Agent", Constants.USER_AGENT)
                    .header("Range", "bytes=0-0")
                    .GET().build();
            HttpResponse<byte[]> resp = client().send(req, HttpResponse.BodyHandlers.ofByteArray());

            String ctype = resp.headers().firstValue("content-type").orElse("");
            if (ctype.toLowerCase().contains("text/html")) {
                throw new IOException("服务器返回网页而非文件（可能被劫持）: " + ctype);
            }
            if (resp.statusCode() == 206) {
                String cr = resp.headers().firstValue("content-range").orElse("");
                int slash = cr.indexOf('/');
                if (slash > 0) {
                    size = Long.parseLong(cr.substring(slash + 1).trim());
                    supportRange = true;
                }
            } else if (resp.statusCode() == 200) {
                size = resp.headers().firstValue("content-length")
                        .map(Long::parseLong).orElse(-1L);
                String accept = resp.headers().firstValue("accept-ranges").orElse("");
                supportRange = accept.toLowerCase().contains("bytes");
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            Log.warn("探测失败: " + e.getMessage());
        }

        if (supportRange && size > MIN_MULTI_SIZE) {
            try {
                multiPart(url, dest, size, progress);
                return;
            } catch (Exception e) {
                Log.warn("分片下载失败，退回单流: " + e.getMessage());
                Files.deleteIfExists(dest);
            }
        }
        singleStream(url, dest, progress);
    }

    /** 分片并发下载 */
    private void multiPart(String url, Path dest, long total, Progress progress)
            throws Exception {
        int segCount = (int) Math.max(1, Math.min(defaultConnections, total / CHUNK));
        long segSize = total / segCount;

        Path partDir = dest.resolveSibling(dest.getFileName() + ".parts");
        Files.createDirectories(partDir);

        AtomicLong done = new AtomicLong();
        long[] perSeg = new long[segCount];
        Object lock = new Object();

        ExecutorService pool = Executors.newFixedThreadPool(segCount);
        List<Future<Void>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < segCount; i++) {
                final int idx = i;
                final long start = i * segSize;
                final long end = (i == segCount - 1) ? total - 1 : (start + segSize - 1);
                futures.add(pool.submit((Callable<Void>) () -> {
                    fetchSegment(url, partDir, idx, start, end,
                            got -> {
                                synchronized (lock) {
                                    long delta = got - perSeg[idx];
                                    perSeg[idx] = got;
                                    long now = done.addAndGet(delta);
                                    if (progress != null) progress.onProgress(now, total);
                                }
                            });
                    return null;
                }));
            }
            for (Future<Void> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }

        // 合并
        try (RandomAccessFile out = new RandomAccessFile(dest.toFile(), "rw")) {
            out.setLength(0);
            for (int i = 0; i < segCount; i++) {
                Path p = partDir.resolve(i + ".part");
                byte[] buf = Files.readAllBytes(p);
                out.write(buf);
            }
        }
        // 清理分片
        for (int i = 0; i < segCount; i++) {
            Files.deleteIfExists(partDir.resolve(i + ".part"));
        }
        Files.deleteIfExists(partDir);
    }

    private interface SegProgress {
        void update(long got);
    }

    /** 下载单个分片（支持断点续传） */
    private void fetchSegment(String url, Path partDir, int idx,
                              long start, long end, SegProgress cb) throws Exception {
        Path segFile = partDir.resolve(idx + ".part");
        long want = end - start + 1;
        long have = Files.exists(segFile) ? Files.size(segFile) : 0;
        if (have >= want) {
            cb.update(want);
            return;
        }

        int attempts = 0;
        while (attempts < 5) {
            attempts++;
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(60))
                        .header("User-Agent", Constants.USER_AGENT)
                        .header("Range", "bytes=" + (start + have) + "-" + end)
                        .GET().build();
                HttpResponse<InputStream> resp = client().send(req, HttpResponse.BodyHandlers.ofInputStream());

                if (resp.statusCode() != 200 && resp.statusCode() != 206) {
                    throw new IOException("HTTP " + resp.statusCode());
                }
                long cur = have;
                try (RandomAccessFile raf = new RandomAccessFile(segFile.toFile(), "rw");
                     InputStream in = resp.body()) {
                    raf.seek(have);
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        raf.write(buf, 0, n);
                        cur += n;
                        cb.update(cur);
                        if (cur >= want) break;
                    }
                }
                if (Files.size(segFile) >= want) {
                    return;
                }
                have = Files.size(segFile);
            } catch (Exception e) {
                have = Files.exists(segFile) ? Files.size(segFile) : 0;
                if (attempts >= 5) throw e;
                Thread.sleep(800L * attempts);
            }
        }
    }

    /** 单流下载 */
    private void singleStream(String url, Path dest, Progress progress) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .header("User-Agent", Constants.USER_AGENT)
                .GET().build();
        HttpResponse<InputStream> resp = client().send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            throw new IOException("HTTP " + resp.statusCode());
        }
        String ctype = resp.headers().firstValue("content-type").orElse("").toLowerCase();
        if (ctype.contains("text/html")) {
            throw new IOException("服务器返回网页而非文件（可能被劫持）");
        }
        long total = resp.headers().firstValue("content-length")
                .map(Long::parseLong).orElse(-1L);
        long cur = 0;
        try (InputStream in = resp.body();
             var out = Files.newOutputStream(dest)) {
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                cur += n;
                if (progress != null) progress.onProgress(cur, total);
            }
        }
        if (total > 0 && cur != total) {
            throw new IOException("下载不完整: " + cur + "/" + total);
        }
    }

    // ---------------- 工具 ----------------

    public static String sha1(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static String humanSize(long bytes) {
        if (bytes < 0) return "未知";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / 1024.0 / 1024);
        return String.format("%.2f GB", bytes / 1024.0 / 1024 / 1024);
    }

    private static String shorten(String url) {
        return url.length() > 90 ? url.substring(0, 90) + "..." : url;
    }
}
