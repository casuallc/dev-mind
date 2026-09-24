package com.devmind.bookmark.service;

import com.devmind.auth.IdentityService;
import com.devmind.auth.model.UserEntity;
import com.devmind.bookmark.config.BookmarkCipher;
import com.devmind.bookmark.config.BookmarkProperties;
import com.devmind.bookmark.dto.BookmarkRequest;
import com.devmind.bookmark.dto.BookmarkView;
import com.devmind.bookmark.dto.ProbeResultView;
import com.devmind.bookmark.model.BookmarkEntity;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-64 FR-04 探测：对真实本地 HTTP 端出站，钉住 HEAD→GET 回落、状态分类、异常同义码、
 * 重定向上限、私有地址开关与结果落库（last_status/last_status_code/last_latency_ms/last_checked_at）。
 */
class BookmarkProbeServiceTest {

    private HttpServer server;
    private String base;
    private FakeBookmarkRepos repo;
    private BookmarkService service;
    private BookmarkProbeService probeService;
    private BookmarkProperties props;

    @BeforeEach
    void setUp() {
        repo = new FakeBookmarkRepos();
        repo.users.put("alice", FakeBookmarkRepos.user("alice", UserEntity.ROLE_DEVELOPER));
        props = new BookmarkProperties();
        props.setCryptoKey("devmind-bookmark-unit-test-key");
        rebuild(10, true);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    /** 按指定探测参数重组服务（探针的 HttpClient 在构造期定超时，改参数必须重建）。 */
    private void rebuild(int timeoutSeconds, boolean allowPrivate) {
        props.getProbe().setTimeoutSeconds(timeoutSeconds);
        props.getProbe().setAllowPrivate(allowPrivate);
        BookmarkOwnership ownership = new BookmarkOwnership(new IdentityService(null, null, null) {
            @Override
            public String currentActor() {
                return "alice";
            }

            @Override
            public Optional<UserEntity> currentUser() {
                return Optional.empty();
            }
        });
        BookmarkCipher cipher = new BookmarkCipher(props);
        cipher.init();
        BookmarkViews views = new BookmarkViews(repo.rels(), repo.tags(), repo.accounts());
        probeService = new BookmarkProbeService(repo.bookmarks(), props, ownership);
        service = new BookmarkService(repo.bookmarks(), repo.groups(), repo.tags(), repo.rels(),
                repo.accounts(), repo.shares(), cipher, ownership, views, probeService);
    }

    private void start(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private Long saved(String url) {
        BookmarkView v = service.create(new BookmarkRequest("探测目标", url, null, null, null, null, null, null));
        return Long.valueOf(v.id());
    }

    private static void respond(HttpExchange ex, int code) throws IOException {
        ex.sendResponseHeaders(code, -1);
        ex.close();
    }

    // ---------------- 状态分类 ----------------

    @Test
    void headOkIsOkAndPersistsResult() throws IOException {
        start(ex -> respond(ex, 200));
        Long id = saved(base + "/ok");
        ProbeResultView r = service.probe(id);

        assertEquals(BookmarkEntity.STATUS_OK, r.status());
        assertEquals("200", r.statusCode());
        assertNotNull(r.latencyMs());
        assertNotNull(r.checkedAt());

        BookmarkView persisted = service.get(id);
        assertEquals(BookmarkEntity.STATUS_OK, persisted.lastStatus());
        assertEquals("200", persisted.lastStatusCode());
        assertNotNull(persisted.lastCheckedAt());
        assertNotNull(persisted.lastLatencyMs());
    }

    @Test
    void head405FallsBackToRangeGet() throws IOException {
        AtomicReference<String> range = new AtomicReference<>();
        AtomicReference<String> method = new AtomicReference<>();
        start(ex -> {
            method.set(ex.getRequestMethod());
            if ("HEAD".equals(ex.getRequestMethod())) {
                respond(ex, 405);
            } else {
                range.set(ex.getRequestHeaders().getFirst("Range"));
                respond(ex, 200);
            }
        });
        ProbeResultView r = service.probe(saved(base + "/fallback"));

        assertEquals("GET", method.get(), "HEAD 不支持时回落 GET");
        assertEquals("bytes=0-0", range.get(), "回落 GET 必须带 Range 只取 0 字节");
        assertEquals(BookmarkEntity.STATUS_OK, r.status());
        assertEquals("200", r.statusCode());
    }

    @Test
    void nonSuccessCodesAreFail() throws IOException {
        start(ex -> respond(ex, "/notfound".equals(ex.getRequestURI().getPath()) ? 404 : 500));

        ProbeResultView four = service.probe(saved(base + "/notfound"));
        assertEquals(BookmarkEntity.STATUS_FAIL, four.status());
        assertEquals("404", four.statusCode(), "站点活着但入口不可用 = FAIL");

        ProbeResultView five = service.probe(saved(base + "/boom"));
        assertEquals(BookmarkEntity.STATUS_FAIL, five.status());
        assertEquals("500", five.statusCode());
    }

    @Test
    void redirectsAreFollowed() throws IOException {
        start(ex -> {
            String p = ex.getRequestURI().getPath();
            if ("/a".equals(p)) {
                ex.getResponseHeaders().add("Location", "/b");
                respond(ex, 302);
            } else if ("/b".equals(p)) {
                ex.getResponseHeaders().add("Location", "/c");
                respond(ex, 301);
            } else {
                respond(ex, 200);
            }
        });
        ProbeResultView r = service.probe(saved(base + "/a"));
        assertEquals(BookmarkEntity.STATUS_OK, r.status());
        assertEquals("200", r.statusCode(), "跟随 302/301 到终点");
    }

    @Test
    void extraRedirectsStopAtLimitAndKeepLastStatus() throws IOException {
        // 每跳都指回自己：到上限就停，最终响应仍是 3xx（FR-04：2xx/3xx 判 OK）
        start(ex -> {
            ex.getResponseHeaders().add("Location", ex.getRequestURI().getPath());
            respond(ex, 302);
        });
        ProbeResultView r = service.probe(saved(base + "/loop"));
        assertEquals(BookmarkEntity.STATUS_OK, r.status());
        assertEquals("302", r.statusCode());
    }

    // ---------------- 异常同义码 ----------------

    @Test
    void unknownHostIsDnsFail() {
        ProbeResultView r = service.probe(saved("https://no-such-host.devmind-invalid/a"));
        assertEquals(BookmarkEntity.STATUS_FAIL, r.status());
        assertEquals("DNS", r.statusCode());
    }

    @Test
    void connectionRefusedIsConnectFail() throws IOException {
        start(ex -> respond(ex, 200));
        int port = server.getAddress().getPort();
        server.stop(0);
        server = null;

        ProbeResultView r = service.probe(saved("http://127.0.0.1:" + port + "/gone"));
        assertEquals(BookmarkEntity.STATUS_FAIL, r.status());
        assertEquals("CONNECT", r.statusCode());
    }

    @Test
    void timeoutIsTimeoutFail() throws IOException {
        rebuild(1, true);
        start(ex -> {
            try {
                Thread.sleep(3000);
                respond(ex, 200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        ProbeResultView r = service.probe(saved(base + "/slow"));
        assertEquals(BookmarkEntity.STATUS_FAIL, r.status());
        assertEquals("TIMEOUT", r.statusCode());
    }

    @Test
    void privateAddressBlockedWhenSwitchOff() throws IOException {
        start(ex -> respond(ex, 200));
        rebuild(10, false);
        ProbeResultView r = service.probe(saved(base + "/internal"));
        assertEquals(BookmarkEntity.STATUS_FAIL, r.status());
        assertEquals("PRIVATE", r.statusCode(), "开关关掉后内网地址直接判失败，不出站");
    }

    // ---------------- 归属与批量 ----------------

    @Test
    void probeIsOwnerScopedAndUnreachableIsResultNotError() {
        Long mine = saved("https://console.example.com");
        DevMindException e = assertThrows(DevMindException.class, () -> service.probe(99999L));
        assertEquals(ErrorCode.NOT_FOUND, e.getErrorCode());
        assertEquals("收藏不存在: 99999", e.getMessage());

        // 自己的能探：不可达是结果（FAIL），不是异常
        ProbeResultView r = service.probe(mine);
        assertEquals(BookmarkEntity.STATUS_FAIL, r.status());
        assertNotNull(r.latencyMs());
    }

    @Test
    void batchRejectsOverLimitAndAcceptsOwnedOnes() {
        props.getProbe().setBatchLimit(2);
        Long a = saved("https://a.example.com");
        Long b = saved("https://b.example.com");
        Long c = saved("https://c.example.com");

        DevMindException e = assertThrows(DevMindException.class, () -> probeService.probeBatch(List.of(a, b, c)));
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
        assertTrue(e.getMessage().contains("最多 2 条"), e.getMessage());

        assertEquals(2, probeService.probeBatch(List.of(a, b)));
    }
}
