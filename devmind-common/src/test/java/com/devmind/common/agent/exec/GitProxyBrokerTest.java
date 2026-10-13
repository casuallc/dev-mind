package com.devmind.common.agent.exec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-58 FR-06 {@link GitProxyBroker} 纯函数面：key 生成/登记/吊销、代理 URL 构造与反解、
 * origin 归一化。凭据红线：token 只在内存 map，本类无任何落盘。
 */
class GitProxyBrokerTest {

    private GitProxyBroker broker() {
        return new GitProxyBroker("http://127.0.0.1:18377/git");
    }

    @Test
    void keyIs16HexAndUnique() {
        GitProxyBroker b = broker();
        String k1 = b.newKey();
        assertTrue(k1.matches("[0-9a-f]{16}"), k1);
        assertNotEquals(k1, b.newKey());
    }

    @Test
    void registerLookupRevoke() {
        GitProxyBroker b = broker();
        b.register("k1", "https://gitlab.example.com/group/repo.git", "pat-1");
        var e = b.lookup("k1");
        assertTrue(e.isPresent());
        assertEquals("https://gitlab.example.com", e.get().upstreamOrigin());
        assertEquals("pat-1", e.get().token());
        assertEquals(1, b.size());
        b.revoke("k1");
        assertTrue(b.lookup("k1").isEmpty());
        assertEquals(0, b.size());
    }

    @Test
    void blankKeyOrTokenSkipped() {
        GitProxyBroker b = broker();
        b.register("", "https://h/r.git", "pat");
        b.register("k", "https://h/r.git", "");
        b.register(null, "https://h/r.git", "pat");
        b.register("k", "https://h/r.git", null);
        assertEquals(0, b.size());
    }

    @Test
    void registerSameKeyRefreshesToken() {
        // 同需求多会话共用工作树：后到的 launch 以同 key 覆盖登记（token 轮换幂等）
        GitProxyBroker b = broker();
        b.register("k1", "https://gitlab.example.com/g/r.git", "pat-old");
        b.register("k1", "https://gitlab.example.com/g/r.git", "pat-new");
        assertEquals("pat-new", b.lookup("k1").orElseThrow().token());
        assertEquals(1, b.size());
    }

    @Test
    void extractKeyOnlyForOwnProxyUrls() {
        GitProxyBroker b = broker();
        assertEquals("abc123",
                b.extractKey("http://127.0.0.1:18377/git/abc123/group/repo.git"));
        assertNull(b.extractKey("https://gitlab.example.com/group/repo.git"), "裸 origin 不解");
        assertNull(b.extractKey("http://127.0.0.1:9999/git/abc123/r.git"), "别的端口（别的代理）不解");
        assertNull(b.extractKey(null));
        assertNull(b.extractKey("http://127.0.0.1:18377/git/"), "空 key 段不解");
    }

    @Test
    void proxyUrlForPreservesRepoPath() {
        GitProxyBroker b = broker();
        assertEquals("http://127.0.0.1:18377/git/k1/group/repo.git",
                b.proxyUrlFor("k1", "https://gitlab.example.com/group/repo.git"));
        assertEquals("http://127.0.0.1:18377/git/k1/r.git",
                b.proxyUrlFor("k1", "http://git.internal:8081/r.git"));
    }

    @Test
    void originOfNormalizes() {
        assertEquals("https://gitlab.example.com",
                GitProxyBroker.originOf("https://gitlab.example.com/group/repo.git"));
        assertEquals("http://git.internal:8081",
                GitProxyBroker.originOf("http://git.internal:8081/r.git"));
        // URL 里即便带 userinfo（不该有，防御）也剥掉
        assertEquals("https://gitlab.example.com",
                GitProxyBroker.originOf("https://oauth2:x@gitlab.example.com/g/r.git"));
    }

    @Test
    void blankBaseUrlRejected() {
        assertThrows(IllegalArgumentException.class, () -> new GitProxyBroker("  "));
    }
}
