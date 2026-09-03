package com.devmind.integration.service;

import com.devmind.auth.IdentityService;
import com.devmind.auth.config.AuthProperties;
import com.devmind.auth.model.UserEntity;
import com.devmind.auth.repo.UserRepository;
import com.devmind.auth.security.DevMindPrincipal;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.integration.GitIdentityProvider;
import com.devmind.integration.config.IntegrationCipher;
import com.devmind.integration.dto.UserGitCredentialRequest;
import com.devmind.integration.model.UserGitCredentialEntity;
import com.devmind.integration.repo.UserGitCredentialRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-24 UserGitCredentialService 单测（无 Spring 上下文，JDK 动态代理内存 fake）：
 * 覆盖本人作用域隔离、host 判重、邮箱校验、secret 留空不改、resolveAuthor 回退链、
 * personalTokenFor 解密与 host 匹配、自检 host 不一致拒绝。
 */
class UserGitCredentialServiceTest {

    private Map<Long, UserGitCredentialEntity> store;
    private Map<String, UserEntity> users;
    private UserGitCredentialService service;
    private FakeCipher cipher;
    private long seq;

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> iface, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, handler);
    }

    /** 伪加解密：enc1: 前缀 + 原样返回（断言解密结果用，不验证算法本身） */
    static class FakeCipher extends IntegrationCipher {
        FakeCipher() { super(null); }
        @Override public String encrypt(String plaintext) { return "enc1:" + plaintext; }
        @Override public String decrypt(String value) {
            return value != null && value.startsWith("enc1:") ? value.substring(5) : value;
        }
    }

    static class FakeGitOps extends GitRemoteOps {
        @Override public GitResult lsRemote(String remoteUrl, String token) {
            return new GitResult(true, "ok");
        }
    }

    @BeforeEach
    void setUp() {
        store = new HashMap<>();
        users = new HashMap<>();
        seq = 0;
        cipher = new FakeCipher();

        UserGitCredentialRepository credRepo = proxy(UserGitCredentialRepository.class, (p, m, args) ->
                switch (m.getName()) {
                    case "save" -> {
                        UserGitCredentialEntity e = (UserGitCredentialEntity) args[0];
                        if (e.getId() == null) {
                            e.setId(++seq);
                        }
                        store.put(e.getId(), e);
                        yield e;
                    }
                    case "findByUserIdOrderByIdAsc" -> store.values().stream()
                            .filter(e -> e.getUserId().equals(args[0]))
                            .sorted(java.util.Comparator.comparing(UserGitCredentialEntity::getId))
                            .toList();
                    case "findByIdAndUserId" -> Optional.ofNullable(store.get((Long) args[0]))
                            .filter(e -> e.getUserId().equals(args[1]));
                    case "delete" -> {
                        store.remove(((UserGitCredentialEntity) args[0]).getId());
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        UserRepository userRepo = proxy(UserRepository.class, (p, m, args) ->
                switch (m.getName()) {
                    case "findByUsername" -> Optional.ofNullable(users.get((String) args[0]));
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        IdentityService identity = new IdentityService(userRepo, new BCryptPasswordEncoder(), new AuthProperties());
        service = new UserGitCredentialService(credRepo, userRepo, identity, cipher, new FakeGitOps());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private UserEntity addUser(String id, String username, String displayName) {
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setUsername(username);
        u.setDisplayName(displayName);
        users.put(username, u);
        return u;
    }

    private void loginAs(String username) {
        var auth = new UsernamePasswordAuthenticationToken(
                new DevMindPrincipal(username, "DEVELOPER"), null,
                List.of(new SimpleGrantedAuthority("ROLE_DEVELOPER")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private UserGitCredentialRequest req(String label, String baseUrl, String secret,
                                         String name, String email) {
        return new UserGitCredentialRequest(label, baseUrl, secret, name, email);
    }

    @Test
    void 创建与列表按本人隔离() {
        addUser("u1", "alice", "Alice");
        addUser("u2", "bob", "Bob");
        loginAs("alice");
        var v = service.create(req("公司 GitLab", "https://gitlab.example.com", "pat-a",
                "Alice", "alice@example.com"));
        assertEquals("enc1:pat-a", store.get(v.id()).getSecretEnc());
        assertTrue(v.hasSecret());

        loginAs("bob");
        assertTrue(service.listMine().isEmpty());
        assertThrows(DevMindException.class, () -> service.update(v.id(),
                req("x", "https://gitlab.example.com", null, "X", "x@example.com")));
        assertThrows(DevMindException.class, () -> service.delete(v.id()));
    }

    @Test
    void 同host判重与邮箱校验() {
        addUser("u1", "alice", "Alice");
        loginAs("alice");
        service.create(req("a", "https://gitlab.example.com", "p1", "Alice", "alice@example.com"));
        assertThrows(DevMindException.class, () -> service.create(
                req("b", "https://gitlab.example.com/", "p2", "Alice", "alice@example.com")));
        assertThrows(DevMindException.class, () -> service.create(
                req("c", "https://github.com", "p3", "Alice", "not-an-email")));
        assertThrows(DevMindException.class, () -> service.create(
                req("d", "ssh://git@x.com", "p4", "Alice", "a@b.com")));
        assertThrows(DevMindException.class, () -> service.create(
                req("e", "https://github.com", null, "Alice", "a@b.com")));
    }

    @Test
    void 更新secret留空不变() {
        addUser("u1", "alice", "Alice");
        loginAs("alice");
        var v = service.create(req("a", "https://gitlab.example.com", "old-token",
                "Alice", "alice@example.com"));
        service.update(v.id(), req("a2", "https://gitlab.example.com", null,
                "Alice Z", "alice.z@example.com"));
        assertEquals("enc1:old-token", store.get(v.id()).getSecretEnc());
        assertEquals("Alice Z", store.get(v.id()).getGitAuthorName());
        service.update(v.id(), req("a2", "https://gitlab.example.com", "new-token",
                "Alice Z", "alice.z@example.com"));
        assertEquals("enc1:new-token", store.get(v.id()).getSecretEnc());
    }

    @Test
    void resolveAuthor优先级与回退() {
        addUser("u1", "alice", "Alice 爱丽");
        loginAs("alice");
        service.create(req("a", "https://gitlab.example.com", "p", "Alice GL", "alice@gl.com"));

        // 命中个人凭证
        var hit = service.resolveAuthor("alice", "gitlab.example.com");
        assertEquals(new GitIdentityProvider.GitAuthor("Alice GL", "alice@gl.com"), hit.orElseThrow());
        // 未匹配 host → 回退 displayName，email 为 null
        var fallback = service.resolveAuthor("alice", "github.com");
        assertEquals(new GitIdentityProvider.GitAuthor("Alice 爱丽", null), fallback.orElseThrow());
        // 无远端 host → 同样回退 displayName
        var noHost = service.resolveAuthor("alice", null);
        assertEquals(new GitIdentityProvider.GitAuthor("Alice 爱丽", null), noHost.orElseThrow());
        // 未知用户 → empty
        assertTrue(service.resolveAuthor("ghost", "gitlab.example.com").isEmpty());
    }

    @Test
    void personalTokenFor按host匹配并解密() {
        addUser("u1", "alice", "Alice");
        loginAs("alice");
        service.create(req("a", "https://gitlab.example.com", "pat-secret", "Alice", "a@b.com"));

        assertEquals("pat-secret",
                service.personalTokenFor("alice", "GITLAB.EXAMPLE.COM").orElseThrow());
        assertTrue(service.personalTokenFor("alice", "github.com").isEmpty());
        assertTrue(service.personalTokenFor("alice", null).isEmpty());
        assertTrue(service.personalTokenFor("ghost", "gitlab.example.com").isEmpty());
    }

    @Test
    void 自检host不一致拒绝() {
        addUser("u1", "alice", "Alice");
        loginAs("alice");
        var v = service.create(req("a", "https://gitlab.example.com", "p", "Alice", "a@b.com"));

        assertThrows(DevMindException.class,
                () -> service.test(v.id(), "https://github.com/o/r.git"));
        assertThrows(DevMindException.class, () -> service.test(v.id(), null));
        assertEquals("连接成功", service.test(v.id(), "https://gitlab.example.com/g/r.git"));
    }
}
