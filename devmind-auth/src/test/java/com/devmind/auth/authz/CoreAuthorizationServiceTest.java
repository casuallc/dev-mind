package com.devmind.auth.authz;

import com.devmind.auth.menu.MenuPermissionService;
import com.devmind.auth.model.RoleEntity;
import com.devmind.auth.model.UserEntity;
import com.devmind.auth.repo.RoleRepository;
import com.devmind.auth.repo.UserRepository;
import com.devmind.common.authz.AuthorizationAuditEvent;
import com.devmind.common.authz.AuthorizationAuditSink;
import com.devmind.common.authz.AuthorizationDecision;
import com.devmind.common.authz.AuthorizationException;
import com.devmind.common.authz.ConfirmationVerifier;
import com.devmind.common.authz.ExecutionContext;
import com.devmind.common.authz.ExecutionContextHolder;
import com.devmind.common.authz.ExecutionSource;
import com.devmind.common.authz.GrantRule;
import com.devmind.common.authz.OperationEffect;
import com.devmind.common.authz.OperationSpec;
import com.devmind.common.authz.OperationSpecProvider;
import com.devmind.common.authz.ReasonCodes;
import com.devmind.common.authz.ResolvedSubject;
import com.devmind.common.authz.ResourcePolicy;
import com.devmind.common.authz.ResourceTarget;
import com.devmind.common.authz.SubjectType;
import com.devmind.common.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CoreAuthorizationServiceTest {

    private final UserRepository users = mock(UserRepository.class);
    private final RoleRepository roles = mock(RoleRepository.class);
    private final MenuPermissionService menus = mock(MenuPermissionService.class);
    private final List<AuthorizationAuditEvent> audited = new ArrayList<>();
    private final AuthorizationAuditSink sink = audited::add;

    private ResourcePolicy policy = allowAllPolicy();
    private ConfirmationVerifier verifier = (ctx, op, target) -> null;
    private CoreAuthorizationService service;

    private static ResourcePolicy allowAllPolicy() {
        return new ResourcePolicy() {
            @Override
            public String policyId() {
                return "P";
            }

            @Override
            public AuthorizationDecision evaluate(ResolvedSubject s, OperationSpec op, ResourceTarget t) {
                return AuthorizationDecision.allow();
            }
        };
    }

    @BeforeEach
    void setUp() {
        OperationSpec readOp = OperationSpec.of("test.read", "test", OperationEffect.READ,
                Set.of("ADMIN", "DEVELOPER", "VIEWER"), GrantRule.none(), "P", "PROJECT");
        OperationSpec writeOp = OperationSpec.of("test.write", "test", OperationEffect.WRITE,
                Set.of("ADMIN", "DEVELOPER"), GrantRule.anyOf("project.requirements.action.create"),
                "P", "PROJECT");
        OperationSpecProvider provider = () -> List.of(readOp, writeOp);
        OperationRegistry registry = new OperationRegistry(
                OperationRegistryTest.providerOf(List.of(provider)),
                OperationRegistryTest.providerOf(List.of(policy)));
        ObjectProvider<ConfirmationVerifier> verifierProvider = OperationRegistryTest.providerOf(
                verifier == null ? List.of() : List.of(verifier));
        ObjectProvider<AuthorizationAuditSink> sinkProvider = OperationRegistryTest.providerOf(List.of(sink));
        service = new CoreAuthorizationService(registry, new SubjectResolver(users, roles),
                menus, verifierProvider, sinkProvider);
    }

    private void withPolicyAndVerifier(ResourcePolicy p, ConfirmationVerifier v) {
        this.policy = p;
        this.verifier = v;
        setUp();
    }

    private void stubUser(String username, String roleCode, String baseRole, boolean active) {
        UserEntity u = new UserEntity();
        u.setId("id-" + username);
        u.setUsername(username);
        u.setRole(baseRole);
        u.setRoleCode(roleCode);
        u.setStatus(active ? UserEntity.STATUS_ACTIVE : UserEntity.STATUS_DISABLED);
        when(users.findByUsername(username)).thenReturn(Optional.of(u));
        RoleEntity r = new RoleEntity();
        r.setCode(roleCode);
        r.setBaseRole(baseRole);
        when(roles.findById(roleCode)).thenReturn(Optional.of(r));
    }

    private ExecutionContext web(String username) {
        return ExecutionContext.builder(SubjectType.USER, username, ExecutionSource.WEB).build();
    }

    @AfterEach
    void tearDown() {
        ExecutionContextHolder.clear();
    }

    @Test
    void 未登记操作报ERROR映射503() {
        try (var ignored = ExecutionContextHolder.set(web("alice"))) {
            stubUser("alice", "DEVELOPER", "DEVELOPER", true);
            AuthorizationException e = assertThrows(AuthorizationException.class,
                    () -> service.require("no.such.op", ResourceTarget.none()));
            assertEquals(ErrorCode.SERVICE_UNAVAILABLE, e.getErrorCode());
            assertEquals(ReasonCodes.OPERATION_UNKNOWN, e.getReasonCode());
        }
    }

    @Test
    void 缺失可信上下文拒绝映射401() {
        ExecutionContextHolder.clear();
        AuthorizationException e = assertThrows(AuthorizationException.class,
                () -> service.require("test.read", ResourceTarget.none()));
        assertEquals(ErrorCode.UNAUTHORIZED, e.getErrorCode());
    }

    @Test
    void 停用用户拒绝且回退local不存在() {
        try (var ignored = ExecutionContextHolder.set(web("bob"))) {
            stubUser("bob", "DEVELOPER", "DEVELOPER", false);
            AuthorizationException e = assertThrows(AuthorizationException.class,
                    () -> service.require("test.read", ResourceTarget.none()));
            assertEquals(ErrorCode.UNAUTHORIZED, e.getErrorCode());
            assertEquals(ReasonCodes.SUBJECT_INACTIVE, e.getReasonCode());
        }
    }

    @Test
    void VIEWER执行写操作按基础角色拒绝() {
        try (var ignored = ExecutionContextHolder.set(web("vic"))) {
            stubUser("vic", "VIEWER", "VIEWER", true);
            AuthorizationException e = assertThrows(AuthorizationException.class,
                    () -> service.require("test.write", ResourceTarget.project("p1")));
            assertEquals(ErrorCode.FORBIDDEN, e.getErrorCode());
            assertEquals(ReasonCodes.ROLE_DENIED, e.getReasonCode());
        }
    }

    @Test
    void 自定义角色缺ACTION拒绝_有ACTION放行() {
        try (var ignored = ExecutionContextHolder.set(web("dev"))) {
            stubUser("dev", "CUSTOM_DEV", "DEVELOPER", true);
            when(menus.hasAnyGrant(anyString(), anyCollection())).thenReturn(false);
            AuthorizationException e = assertThrows(AuthorizationException.class,
                    () -> service.require("test.write", ResourceTarget.project("p1")));
            assertEquals(ReasonCodes.ACTION_DENIED, e.getReasonCode());

            when(menus.hasAnyGrant(anyString(), anyCollection())).thenReturn(true);
            service.require("test.write", ResourceTarget.project("p1"));
        }
    }

    @Test
    void 资源不可见统一404() {
        withPolicyAndVerifier(new ResourcePolicy() {
            @Override
            public String policyId() {
                return "P";
            }

            @Override
            public AuthorizationDecision evaluate(ResolvedSubject s, OperationSpec op, ResourceTarget t) {
                return AuthorizationDecision.deny(ReasonCodes.RESOURCE_HIDDEN, "不可见");
            }
        }, verifier);
        try (var ignored = ExecutionContextHolder.set(web("dev"))) {
            stubUser("dev", "DEVELOPER", "DEVELOPER", true);
            when(menus.hasAnyGrant(anyString(), anyCollection())).thenReturn(true);
            AuthorizationException e = assertThrows(AuthorizationException.class,
                    () -> service.require("test.write", ResourceTarget.project("p1")));
            assertEquals(ErrorCode.NOT_FOUND, e.getErrorCode());
        }
    }

    @Test
    void AI来源写命令缺确认凭据拒绝() {
        withPolicyAndVerifier(policy, (ctx, op, target) -> "确认单不存在或已过期");
        ExecutionContext ai = ExecutionContext.builder(SubjectType.USER, "dev", ExecutionSource.BOT)
                .actionId("act-1").build();
        try (var ignored = ExecutionContextHolder.set(ai)) {
            stubUser("dev", "DEVELOPER", "DEVELOPER", true);
            when(menus.hasAnyGrant(anyString(), anyCollection())).thenReturn(true);
            AuthorizationException e = assertThrows(AuthorizationException.class,
                    () -> service.require("test.write", ResourceTarget.project("p1")));
            assertEquals(ReasonCodes.CONFIRMATION_REQUIRED, e.getReasonCode());
        }
    }

    @Test
    void AI来源写命令确认凭据缺失校验器报ERROR() {
        withPolicyAndVerifier(policy, null);
        ExecutionContext ai = ExecutionContext.builder(SubjectType.USER, "dev", ExecutionSource.BOT).build();
        try (var ignored = ExecutionContextHolder.set(ai)) {
            stubUser("dev", "DEVELOPER", "DEVELOPER", true);
            when(menus.hasAnyGrant(anyString(), anyCollection())).thenReturn(true);
            AuthorizationException e = assertThrows(AuthorizationException.class,
                    () -> service.require("test.write", ResourceTarget.project("p1")));
            assertEquals(ErrorCode.SERVICE_UNAVAILABLE, e.getErrorCode());
        }
    }

    @Test
    void ADMIN绕过角色与ACTION但资源策略仍执行() {
        ResourcePolicy ownerOnly = new ResourcePolicy() {
            @Override
            public String policyId() {
                return "P";
            }

            @Override
            public AuthorizationDecision evaluate(ResolvedSubject s, OperationSpec op, ResourceTarget t) {
                if (s.system()) {
                    return AuthorizationDecision.allow();
                }
                return s.username().equals(t.ownerId())
                        ? AuthorizationDecision.allow()
                        : AuthorizationDecision.deny(ReasonCodes.OWNER_DENIED, "非本人");
            }
        };
        withPolicyAndVerifier(ownerOnly, verifier);
        try (var ignored = ExecutionContextHolder.set(web("root"))) {
            stubUser("root", "ADMIN", "ADMIN", true);
            // ADMIN 不因基础角色扩展 OWNER_ONLY 对象
            AuthorizationException e = assertThrows(AuthorizationException.class,
                    () -> service.require("test.write", ResourceTarget.owned("WORKLOG", "w1", "other")));
            assertEquals(ReasonCodes.OWNER_DENIED, e.getReasonCode());
            // 本人则放行（ROLE/ACTION 被绕过，不需要 ACTION grant）
            service.require("test.write", ResourceTarget.owned("WORKLOG", "w2", "root"));
        }
    }

    @Test
    void 每次判定写审计() {
        try (var ignored = ExecutionContextHolder.set(web("dev"))) {
            stubUser("dev", "DEVELOPER", "DEVELOPER", true);
            when(menus.hasAnyGrant(anyString(), anyCollection())).thenReturn(true);
            service.require("test.write", ResourceTarget.project("p1"));
            assertThrows(AuthorizationException.class,
                    () -> service.require("unknown.op", ResourceTarget.none()));
            assertEquals(2, audited.size());
            assertEquals("ALLOW", audited.get(0).decision());
            assertEquals("ERROR", audited.get(1).decision());
            assertEquals("test.write", audited.get(0).operationId());
            assertEquals("DEVELOPER", audited.get(0).roleCode());
        }
    }

    @Test
    void 系统任务主体绕过角色ACTION但过资源策略() {
        try (var ignored = ExecutionContextHolder.set(ExecutionContext.system("job"))) {
            when(menus.hasAnyGrant(anyString(), anyCollection())).thenReturn(false);
            service.require("test.write", ResourceTarget.project("p1"));
            assertTrue(audited.stream().anyMatch(e -> "ALLOW".equals(e.decision())));
        }
    }
}
