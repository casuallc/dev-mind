package com.devmind.integration.service;

import com.devmind.auth.IdentityService;
import com.devmind.auth.config.AuthProperties;
import com.devmind.auth.model.UserEntity;
import com.devmind.auth.repo.UserRepository;
import com.devmind.auth.security.DevMindPrincipal;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.integration.GitIdentityProvider;
import com.devmind.integration.config.IntegrationCipher;
import com.devmind.integration.connector.IntegrationConnector;
import com.devmind.integration.dto.PlatformAccountUpsertRequest;
import com.devmind.integration.dto.PlatformAccountView;
import com.devmind.integration.model.IntegrationEntity;
import com.devmind.integration.model.UserPlatformAccountEntity;
import com.devmind.integration.repo.IntegrationRepository;
import com.devmind.integration.repo.UserPlatformAccountRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-35 UserPlatformAccountService 单测（无 Spring 上下文，JDK 动态代理内存 fake）：
 * 覆盖实例绑定 upsert/校验（git PAT+署名、Jira BASIC）、secret 留空不改、停用实例拒绑、
 * resolveAuthor 回退链、personalTokenFor/personalSecretFor 解析、自检走 Connector。
 */
class UserPlatformAccountServiceTest {

    private Map<Long, UserPlatformAccountEntity> accounts;
    private Map<Long, IntegrationEntity> integrations;
    private Map<String, UserEntity> users;
    private UserPlatformAccountService service;
    private FakeCipher cipher;
    private long accountSeq;
    private long integrationSeq;

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

    /** 伪 Connector（JDK 代理）：testConnection 记录收到的 token 并回成功，其余方法抛不支持 */
    static class FakeConnector {
        final String type;
        String lastToken;
        FakeConnector(String type) { this.type = type; }

        IntegrationConnector proxy() {
            return UserPlatformAccountServiceTest.proxy(IntegrationConnector.class, (p, m, args) -> {
                if (m.getName().equals("type")) {
                    return type;
                }
                if (m.getName().equals("testConnection")) {
                    lastToken = (String) args[1];
                    return new IntegrationConnector.TestResult(true, "连接成功",
                            ((IntegrationEntity) args[0]).getBaseUrl());
                }
                throw new UnsupportedOperationException(m.getName());
            });
        }
    }

    private FakeConnector gitlabConnector;
    private FakeConnector jiraConnector;

    @BeforeEach
    void setUp() {
        accounts = new HashMap<>();
        integrations = new HashMap<>();
        users = new HashMap<>();
        accountSeq = 0;
        integrationSeq = 0;
        cipher = new FakeCipher();
        gitlabConnector = new FakeConnector(IntegrationEntity.TYPE_GITLAB);
        jiraConnector = new FakeConnector(IntegrationEntity.TYPE_JIRA);

        UserPlatformAccountRepository accountRepo = proxy(UserPlatformAccountRepository.class,
                (p, m, args) -> switch (m.getName()) {
                    case "save" -> {
                        UserPlatformAccountEntity e = (UserPlatformAccountEntity) args[0];
                        if (e.getId() == null) {
                            e.setId(++accountSeq);
                        }
                        accounts.put(e.getId(), e);
                        yield e;
                    }
                    case "findByUserIdOrderByCreatedAtDesc" -> accounts.values().stream()
                            .filter(e -> e.getUserId().equals(args[0]))
                            .sorted(java.util.Comparator.comparing(UserPlatformAccountEntity::getId))
                            .toList();
                    case "findByUserIdAndIntegrationId" -> accounts.values().stream()
                            .filter(e -> e.getUserId().equals(args[0])
                                    && e.getIntegrationId().equals(args[1]))
                            .findFirst();
                    case "delete" -> {
                        accounts.remove(((UserPlatformAccountEntity) args[0]).getId());
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        IntegrationRepository integrationRepo = proxy(IntegrationRepository.class,
                (p, m, args) -> switch (m.getName()) {
                    case "findById" -> Optional.ofNullable(integrations.get((Long) args[0]));
                    case "findAllByOrderByCreatedAtDesc" -> new ArrayList<>(integrations.values());
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        UserRepository userRepo = proxy(UserRepository.class, (p, m, args) ->
                switch (m.getName()) {
                    case "findByUsername" -> Optional.ofNullable(users.get((String) args[0]));
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        IdentityService identity = new IdentityService(userRepo, new BCryptPasswordEncoder(),
                new AuthProperties());
        service = new UserPlatformAccountService(accountRepo, integrationRepo, userRepo, identity,
                cipher, List.of(gitlabConnector.proxy(), jiraConnector.proxy()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private IntegrationEntity addIntegration(String type, String name, String baseUrl) {
        IntegrationEntity e = new IntegrationEntity();
        e.setId(++integrationSeq);
        e.setType(type);
        e.setName(name);
        e.setBaseUrl(baseUrl);
        e.setStatus(IntegrationEntity.STATUS_ENABLED);
        integrations.put(e.getId(), e);
        return e;
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

    private static PlatformAccountUpsertRequest gitReq(String secret, String name, String email) {
        return new PlatformAccountUpsertRequest("PAT", null, secret, name, email);
    }

    @Test
    void 绑定与列表含未绑定实例() {
        IntegrationEntity gl = addIntegration("GITLAB", "研发 GitLab", "https://gitlab.example.com");
        IntegrationEntity jira = addIntegration("JIRA", "公司 Jira", "https://jira.example.com");
        addUser("u1", "alice", "Alice");
        loginAs("alice");

        PlatformAccountView bound = service.upsert(gl.getId(),
                gitReq("pat-a", "Alice", "alice@example.com"));
        assertTrue(bound.bound());
        assertEquals("enc1:pat-a", accounts.values().iterator().next().getSecretEnc());

        List<PlatformAccountView> overview = service.listOverview();
        assertEquals(2, overview.size());
        assertTrue(overview.get(0).bound());
        assertFalse(overview.get(1).bound());
        assertEquals(jira.getId(), overview.get(1).integrationId());
    }

    @Test
    void 校验规则() {
        IntegrationEntity gl = addIntegration("GITLAB", "GL", "https://gitlab.example.com");
        IntegrationEntity jira = addIntegration("JIRA", "Jira", "https://jira.example.com");
        addUser("u1", "alice", "Alice");
        loginAs("alice");

        // git 平台：署名必填、邮箱合法、仅 PAT、secret 必填
        assertThrows(DevMindException.class, () -> service.upsert(gl.getId(),
                gitReq("p", "", "a@b.com")));
        assertThrows(DevMindException.class, () -> service.upsert(gl.getId(),
                gitReq("p", "A", "not-an-email")));
        assertThrows(DevMindException.class, () -> service.upsert(gl.getId(),
                new PlatformAccountUpsertRequest("BASIC", "u", "p", "A", "a@b.com")));
        assertThrows(DevMindException.class, () -> service.upsert(gl.getId(),
                gitReq(null, "A", "a@b.com")));
        // Jira BASIC：username 必填
        assertThrows(DevMindException.class, () -> service.upsert(jira.getId(),
                new PlatformAccountUpsertRequest("BASIC", null, "pw", null, null)));
        // 不存在的实例 / 停用实例
        assertThrows(DevMindException.class, () -> service.upsert(999L,
                gitReq("p", "A", "a@b.com")));
        jira.setStatus(IntegrationEntity.STATUS_DISABLED);
        assertThrows(DevMindException.class, () -> service.upsert(jira.getId(),
                new PlatformAccountUpsertRequest("PAT", null, "p", null, null)));
    }

    @Test
    void jiraBasic密文格式与更新留空不改() {
        IntegrationEntity jira = addIntegration("JIRA", "Jira", "https://jira.example.com");
        addUser("u1", "alice", "Alice");
        loginAs("alice");

        service.upsert(jira.getId(),
                new PlatformAccountUpsertRequest("BASIC", "alice.j", "pw1", null, null));
        UserPlatformAccountEntity e = accounts.values().iterator().next();
        assertEquals("enc1:alice.j\npw1", e.getSecretEnc());
        assertEquals("alice.j", e.getUsername());
        assertNull(e.getGitAuthorName());

        // secret 留空不变
        service.upsert(jira.getId(),
                new PlatformAccountUpsertRequest("BASIC", null, null, null, null));
        assertEquals("enc1:alice.j\npw1", e.getSecretEnc());
        // 换用户名必须重填密码
        assertThrows(DevMindException.class, () -> service.upsert(jira.getId(),
                new PlatformAccountUpsertRequest("BASIC", "alice2", null, null, null)));
        service.upsert(jira.getId(),
                new PlatformAccountUpsertRequest("BASIC", "alice2", "pw2", null, null));
        assertEquals("enc1:alice2\npw2", e.getSecretEnc());
    }

    @Test
    void resolveAuthor经实例host匹配() {
        addIntegration("GITLAB", "GL", "https://gitlab.example.com");
        addUser("u1", "alice", "Alice 爱丽");
        loginAs("alice");
        IntegrationEntity gl = integrations.values().iterator().next();
        service.upsert(gl.getId(), gitReq("p", "Alice GL", "alice@gl.com"));

        var hit = service.resolveAuthor("alice", "gitlab.example.com");
        assertEquals(new GitIdentityProvider.GitAuthor("Alice GL", "alice@gl.com"), hit.orElseThrow());
        // 未匹配 host / 无 host → 回退 displayName
        assertEquals(new GitIdentityProvider.GitAuthor("Alice 爱丽", null),
                service.resolveAuthor("alice", "github.com").orElseThrow());
        assertEquals(new GitIdentityProvider.GitAuthor("Alice 爱丽", null),
                service.resolveAuthor("alice", null).orElseThrow());
        assertTrue(service.resolveAuthor("ghost", "gitlab.example.com").isEmpty());
    }

    @Test
    void personalToken解析() {
        IntegrationEntity gl = addIntegration("GITLAB", "GL", "https://gitlab.example.com");
        addUser("u1", "alice", "Alice");
        loginAs("alice");
        service.upsert(gl.getId(), gitReq("pat-secret", "A", "a@b.com"));

        assertEquals("pat-secret",
                service.personalTokenFor("alice", "GITLAB.EXAMPLE.COM").orElseThrow());
        assertTrue(service.personalTokenFor("alice", "github.com").isEmpty());
        assertTrue(service.personalTokenFor("alice", null).isEmpty());
        assertTrue(service.personalTokenFor("ghost", "gitlab.example.com").isEmpty());
        assertEquals("pat-secret",
                service.personalSecretFor("alice", gl.getId()).orElseThrow());
        assertTrue(service.personalSecretFor("alice", 999L).isEmpty());
    }

    @Test
    void 自检走Connector且用个人凭据() {
        IntegrationEntity gl = addIntegration("GITLAB", "GL", "https://gitlab.example.com");
        addUser("u1", "alice", "Alice");
        loginAs("alice");

        assertThrows(DevMindException.class, () -> service.test(gl.getId()));
        service.upsert(gl.getId(), gitReq("my-pat", "A", "a@b.com"));
        var result = service.test(gl.getId());
        assertTrue(result.ok());
        assertEquals("my-pat", gitlabConnector.lastToken);
    }

    @Test
    void 解绑与本人隔离() {
        IntegrationEntity gl = addIntegration("GITLAB", "GL", "https://gitlab.example.com");
        addUser("u1", "alice", "Alice");
        addUser("u2", "bob", "Bob");
        loginAs("alice");
        service.upsert(gl.getId(), gitReq("p", "A", "a@b.com"));

        loginAs("bob");
        assertThrows(DevMindException.class, () -> service.unbind(gl.getId()));
        assertEquals(1, accounts.size());

        loginAs("alice");
        service.unbind(gl.getId());
        assertTrue(accounts.isEmpty());
    }
}
