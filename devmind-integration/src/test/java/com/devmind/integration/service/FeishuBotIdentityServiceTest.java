package com.devmind.integration.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.devmind.auth.model.UserEntity;
import com.devmind.auth.repo.UserRepository;
import com.devmind.integration.model.IntegrationEntity;
import com.devmind.integration.model.UserPlatformAccountEntity;
import com.devmind.integration.repo.IntegrationRepository;
import com.devmind.integration.repo.UserPlatformAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * CAP-75 FR-11 FeishuBotIdentityService 单测（JDK 动态代理内存 fake）：
 * open_id → 平台用户名解析，只挑「ENABLED + bot 槽启用」的 FEISHU 集成，
 * 非 IDENTITY 绑定/文档专用集成/停用集成均不命中。
 */
class FeishuBotIdentityServiceTest {

    private Map<Long, IntegrationEntity> integrations;
    private List<UserPlatformAccountEntity> accounts;
    private Map<String, UserEntity> usersById;
    private FeishuBotIdentityService service;
    private long seq;

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> iface, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, handler);
    }

    @BeforeEach
    void setUp() {
        integrations = new HashMap<>();
        accounts = new ArrayList<>();
        usersById = new HashMap<>();
        seq = 0;

        IntegrationRepository integrationRepo = proxy(IntegrationRepository.class,
                (p, m, args) -> switch (m.getName()) {
                    case "findAllByOrderByCreatedAtDesc" -> new ArrayList<>(integrations.values());
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        UserPlatformAccountRepository accountRepo = proxy(UserPlatformAccountRepository.class,
                (p, m, args) -> switch (m.getName()) {
                    case "findByIntegrationIdAndUsername" -> accounts.stream()
                            .filter(a -> a.getIntegrationId().equals(args[0])
                                    && args[1].equals(a.getUsername()))
                            .findFirst();
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        UserRepository userRepo = proxy(UserRepository.class, (p, m, args) ->
                switch (m.getName()) {
                    case "findById" -> Optional.ofNullable(usersById.get((String) args[0]));
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        service = new FeishuBotIdentityService(integrationRepo, accountRepo, userRepo);
    }

    private IntegrationEntity addFeishu(String name, String status, String configJson) {
        IntegrationEntity e = new IntegrationEntity();
        e.setId(++seq);
        e.setType(IntegrationEntity.TYPE_FEISHU);
        e.setName(name);
        e.setBaseUrl("https://open.feishu.cn");
        e.setStatus(status);
        e.setConfigJson(configJson);
        integrations.put(e.getId(), e);
        return e;
    }

    private void bind(Long integrationId, String userId, String authType, String openId) {
        UserPlatformAccountEntity a = new UserPlatformAccountEntity();
        a.setId(++seq);
        a.setUserId(userId);
        a.setIntegrationId(integrationId);
        a.setAuthType(authType);
        a.setUsername(openId);
        accounts.add(a);
    }

    private void addUser(String id, String username) {
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setUsername(username);
        usersById.put(id, u);
    }

    @Test
    void 命中启用bot槽的集成() {
        IntegrationEntity bot = addFeishu("机器人", IntegrationEntity.STATUS_ENABLED,
                "{\"bot\":{\"enabled\":true}}");
        addUser("u1", "alice");
        bind(bot.getId(), "u1", IntegrationEntity.AUTH_IDENTITY, "ou_alice");

        assertEquals("alice", service.resolveUsername("ou_alice").orElseThrow());
    }

    @Test
    void 文档专用与停用集成不命中() {
        IntegrationEntity docs = addFeishu("文档专用", IntegrationEntity.STATUS_ENABLED, null);
        IntegrationEntity off = addFeishu("已停用", IntegrationEntity.STATUS_DISABLED,
                "{\"bot\":{}}");
        addUser("u1", "alice");
        addUser("u2", "bob");
        bind(docs.getId(), "u1", IntegrationEntity.AUTH_IDENTITY, "ou_alice");
        bind(off.getId(), "u2", IntegrationEntity.AUTH_IDENTITY, "ou_bob");

        assertTrue(service.resolveUsername("ou_alice").isEmpty());
        assertTrue(service.resolveUsername("ou_bob").isEmpty());
    }

    @Test
    void 非IDENTITY绑定不命中() {
        IntegrationEntity bot = addFeishu("机器人", IntegrationEntity.STATUS_ENABLED, "{\"bot\":{}}");
        addUser("u1", "alice");
        bind(bot.getId(), "u1", IntegrationEntity.AUTH_BASIC, "ou_alice");

        assertTrue(service.resolveUsername("ou_alice").isEmpty());
    }

    @Test
    void 多集成依次查首个命中生效() {
        IntegrationEntity first = addFeishu("机器人A", IntegrationEntity.STATUS_ENABLED,
                "{\"bot\":{}}");
        IntegrationEntity second = addFeishu("机器人B", IntegrationEntity.STATUS_ENABLED,
                "{\"bot\":{}}");
        addUser("u1", "alice");
        addUser("u2", "bob");
        bind(second.getId(), "u2", IntegrationEntity.AUTH_IDENTITY, "ou_bob");
        bind(first.getId(), "u1", IntegrationEntity.AUTH_IDENTITY, "ou_alice");

        assertEquals("alice", service.resolveUsername("ou_alice").orElseThrow());
        assertEquals("bob", service.resolveUsername("ou_bob").orElseThrow());
        assertTrue(service.resolveUsername("ou_ghost").isEmpty());
        assertTrue(service.resolveUsername(null).isEmpty());
        assertTrue(service.resolveUsername("  ").isEmpty());
    }
}
