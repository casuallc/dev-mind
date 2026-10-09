package com.devmind.bookmark.service;

import com.devmind.bookmark.config.BookmarkProperties;
import com.devmind.bookmark.dto.ProbeResultView;
import com.devmind.bookmark.model.BookmarkEntity;
import com.devmind.bookmark.repo.BookmarkRepository;
import com.devmind.common.egress.EgressProxyRouter;
import com.devmind.common.egress.EgressProxySelector;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLException;

/**
 * CAP-64 FR-04 可用性探测。出站 HTTP 由服务端发起：先 HEAD，405/501 回落
 * GET（{@code Range: bytes=0-0}，不取正文）；超时 10s、最多跟随 3 次重定向、仅 http/https。
 * 结果落 last_status / last_status_code / last_latency_ms / last_checked_at。
 *
 * <p>探测本身不会抛业务异常——不可达就是 FAIL 结果（异常原因写进 code 同义描述），
 * 只有「收藏不存在/不属于你」才 404。</p>
 */
@Service
public class BookmarkProbeService {

    private static final Logger log = LoggerFactory.getLogger(BookmarkProbeService.class);

    /** 探测并发度：批量 200 条不能一次打出去 200 个出站连接 */
    private static final int WORKERS = 4;

    private static final AtomicInteger PROBE_SEQ = new AtomicInteger();

    private final BookmarkRepository repo;
    private final BookmarkProperties props;
    private final BookmarkOwnership ownership;

    /** 静态复用 HttpClient：每次调用新建会把连接池也一起废掉（对照 common OpenAiCompatHttp 先例） */
    private final HttpClient client;
    private final ExecutorService executor = Executors.newFixedThreadPool(WORKERS, r -> {
        Thread t = new Thread(r, "bookmark-probe-" + PROBE_SEQ.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    /** 无出口路由的便捷构造（测试与无 agent 模块装配场景；等价于 ObjectProvider 空） */
    public BookmarkProbeService(BookmarkRepository repo,
                                BookmarkProperties props,
                                BookmarkOwnership ownership) {
        this(repo, props, ownership, new ObjectProvider<>() {
            @Override
            public EgressProxyRouter getObject() {
                return null;
            }

            @Override
            public EgressProxyRouter getObject(Object... args) {
                return null;
            }
        });
    }

    public BookmarkProbeService(BookmarkRepository repo,
                                BookmarkProperties props,
                                BookmarkOwnership ownership,
                                ObjectProvider<EgressProxyRouter> egressRouterProvider) {
        this.repo = repo;
        this.props = props;
        this.ownership = ownership;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(props.getProbe().getTimeoutSeconds()))
                // 重定向自己走循环，这样才能精确钉住「最多 3 次」（JDK 的 NORMAL 固定 5 次不可配）
                .followRedirects(HttpClient.Redirect.NEVER)
                // 钉死 HTTP/1.1：新 JDK 默认发 Upgrade: h2c，落 body 的请求会被部分服务端丢（2026-09-20 实锤）
                .version(HttpClient.Version.HTTP_1_1)
                // CAP-70 FR-06：规则驱动 ProxySelector 显式挂载（不设 JVM 全局默认）；
                // router 缺席（agent 模块未装配）= 恒 DIRECT 零行为变化
                .proxy(new EgressProxySelector(egressRouterProvider.getIfAvailable()))
                .build();
    }

    /** 单条探测（同步返回结果）。 */
    public ProbeResultView probe(Long id) {
        String owner = ownership.owner();
        BookmarkEntity e = repo.findByIdAndOwnerId(id, owner)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, notFound(id)));
        return run(e);
    }

    /**
     * 批量探测（异步逐条执行）。入参在这里同步校验归属并收集可探测的 id，
     * 之后交给工作线程只按 id 重查——异步线程内绝不调所有权判定（无 SecurityContext 会退化成 local）。
     *
     * @return 实际受理的条数
     */
    public int probeBatch(List<Long> ids) {
        String owner = ownership.owner();
        int limit = props.getProbe().getBatchLimit();
        List<Long> distinct = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return 0;
        }
        if (distinct.size() > limit) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "批量探测一次最多 " + limit + " 条，本次 " + distinct.size() + " 条");
        }
        List<Long> accepted = distinct.stream()
                .map(id -> repo.findByIdAndOwnerId(id, owner).orElse(null))
                .filter(Objects::nonNull)
                .map(BookmarkEntity::getId)
                .toList();
        for (Long id : accepted) {
            executor.submit(() -> runQuietly(id));
        }
        return accepted.size();
    }

    private void runQuietly(Long id) {
        try {
            repo.findById(id).ifPresent(this::run);
        } catch (Exception ex) {
            // 批量探测的失败不能冒泡到线程池（会静默丢任务），逐条落日志
            log.warn("批量探测异常 bookmarkId={}: {}", id, ex.getMessage());
        }
    }

    /** 探测一条并落库（每次 save 自带事务即时提交，异步线程能立刻看到结果）。 */
    private ProbeResultView run(BookmarkEntity e) {
        Outcome o = execute(e.getUrl());
        Instant now = Instant.now();
        e.setLastStatus(o.status());
        e.setLastStatusCode(o.code());
        e.setLastLatencyMs(o.latencyMs());
        e.setLastCheckedAt(now);
        repo.save(e);
        return new ProbeResultView(String.valueOf(e.getId()), o.status(), o.code(), o.latencyMs(), now);
    }

    private Outcome execute(String url) {
        long start = System.nanoTime();
        String host = BookmarkUrls.hostOf(url);
        if (host == null) {
            return Outcome.fail("BAD_URL", start);
        }
        if (!props.getProbe().isAllowPrivate() && BookmarkUrls.isPrivateHost(host)) {
            return Outcome.fail("PRIVATE", start);
        }
        try {
            URI current = URI.create(url.trim());
            HttpResponse<Void> resp = send(current);
            int hops = 0;
            while (isRedirect(resp.statusCode()) && hops < props.getProbe().getMaxRedirects()) {
                String location = resp.headers().firstValue("location").orElse(null);
                if (location == null || location.isBlank()) {
                    break;
                }
                URI next = current.resolve(location.trim());
                String scheme = next.getScheme() == null ? "" : next.getScheme().toLowerCase(Locale.ROOT);
                if (!scheme.equals("http") && !scheme.equals("https")) {
                    return Outcome.fail("BAD_URL", start);
                }
                current = next;
                hops++;
                resp = send(current);
            }
            // 2xx/3xx 判 OK，其余判 FAIL（含 401/403——站点活着但这条入口不可用）
            int code = resp.statusCode();
            long ms = ms(start);
            return code >= 200 && code < 400
                    ? new Outcome(BookmarkEntity.STATUS_OK, String.valueOf(code), ms)
                    : new Outcome(BookmarkEntity.STATUS_FAIL, String.valueOf(code), ms);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Outcome.fail("ABORTED", start);
        } catch (Exception ex) {
            return Outcome.fail(classify(ex), start);
        }
    }

    /** 一个 hop：先 HEAD；405/501（不支持 HEAD）回落 Range GET。 */
    private HttpResponse<Void> send(URI uri) throws IOException, InterruptedException {
        HttpResponse<Void> head = client.send(headRequest(uri), HttpResponse.BodyHandlers.discarding());
        int code = head.statusCode();
        if (code == 405 || code == 501) {
            return client.send(rangeGetRequest(uri), HttpResponse.BodyHandlers.discarding());
        }
        return head;
    }

    private HttpRequest headRequest(URI uri) {
        return base(uri).method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
    }

    /** 拿 0 字节而不是整页正文（FR-04 明确不取正文）。 */
    private HttpRequest rangeGetRequest(URI uri) {
        return base(uri).header("Range", "bytes=0-0").GET().build();
    }

    private HttpRequest.Builder base(URI uri) {
        return HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(props.getProbe().getTimeoutSeconds()))
                .header("User-Agent", "DevMind-Bookmark-Probe/1.0")
                .header("Accept", "*/*");
    }

    private static boolean isRedirect(int code) {
        return code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
    }

    /**
     * 异常 → 8 字符内的同义码（last_status_code 列长 8）。
     * 走 cause 链找根因：JDK HttpClient 会把 UnknownHostException/ConnectException 包在不同层级。
     * 注意「域名解析不了」在 HttpClient 下抛的是 ConnectException(→ UnresolvedAddressException)，
     * 只认 ConnectException 会把 DNS 错判成 CONNECT，故 DNS 必须先于 CONNECT 判。
     */
    static String classify(Throwable ex) {
        if (ex instanceof HttpTimeoutException) {
            return "TIMEOUT";
        }
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof UnknownHostException || t instanceof UnresolvedAddressException) {
                return "DNS";
            }
        }
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof ConnectException) {
                return "CONNECT";
            }
            if (t instanceof SSLException) {
                return "SSL";
            }
        }
        return ex instanceof IOException ? "IO" : "ERROR";
    }

    private static long ms(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static String notFound(Long id) {
        return "收藏不存在: " + id;
    }

    private record Outcome(String status, String code, long latencyMs) {

        static Outcome fail(String code, long startNanos) {
            return new Outcome(BookmarkEntity.STATUS_FAIL, code, ms(startNanos));
        }
    }
}
