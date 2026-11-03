package com.devmind.auth.authz;

import com.devmind.auth.model.RoleEntity;
import com.devmind.auth.model.UserEntity;
import com.devmind.auth.repo.RoleRepository;
import com.devmind.auth.repo.UserRepository;
import com.devmind.common.authz.ExecutionContext;
import com.devmind.common.authz.ExecutionSource;
import com.devmind.common.authz.ReasonCodes;
import com.devmind.common.authz.SubjectType;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SubjectResolverTest {

    private final UserRepository users = mock(UserRepository.class);
    private final RoleRepository roles = mock(RoleRepository.class);
    private final SubjectResolver resolver = new SubjectResolver(users, roles);

    private ExecutionContext webUser(String username) {
        return ExecutionContext.builder(SubjectType.USER, username, ExecutionSource.WEB).build();
    }

    private UserEntity user(String username, String roleCode, boolean active) {
        UserEntity u = new UserEntity();
        u.setId("id-" + username);
        u.setUsername(username);
        u.setRole("DEVELOPER");
        u.setRoleCode(roleCode);
        u.setStatus(active ? UserEntity.STATUS_ACTIVE : UserEntity.STATUS_DISABLED);
        return u;
    }

    private RoleEntity role(String code, String baseRole) {
        RoleEntity r = new RoleEntity();
        r.setCode(code);
        r.setBaseRole(baseRole);
        return r;
    }

    @Test
    void 用户不存在拒绝SUBJECT_UNKNOWN() {
        when(users.findByUsername("ghost")).thenReturn(Optional.empty());
        var r = resolver.resolve(webUser("ghost"));
        assertFalse(r.resolved());
        assertEquals(ReasonCodes.SUBJECT_UNKNOWN, r.denyReason());
    }

    @Test
    void 停用用户拒绝SUBJECT_INACTIVE() {
        when(users.findByUsername("bob")).thenReturn(Optional.of(user("bob", "DEVELOPER", false)));
        var r = resolver.resolve(webUser("bob"));
        assertFalse(r.resolved());
        assertEquals(ReasonCodes.SUBJECT_INACTIVE, r.denyReason());
    }

    @Test
    void 角色删除拒绝SUBJECT_INACTIVE() {
        when(users.findByUsername("bob")).thenReturn(Optional.of(user("bob", "GONE", true)));
        when(roles.findById("GONE")).thenReturn(Optional.empty());
        var r = resolver.resolve(webUser("bob"));
        assertFalse(r.resolved());
        assertEquals(ReasonCodes.SUBJECT_INACTIVE, r.denyReason());
    }

    @Test
    void 有效主体取角色当前baseRole而非token快照() {
        when(users.findByUsername("alice")).thenReturn(Optional.of(user("alice", "CUSTOM_DEV", true)));
        when(roles.findById("CUSTOM_DEV")).thenReturn(Optional.of(role("CUSTOM_DEV", "DEVELOPER")));
        var r = resolver.resolve(webUser("alice"));
        assertTrue(r.resolved());
        assertEquals("DEVELOPER", r.subject().baseRole());
        assertEquals("CUSTOM_DEV", r.subject().roleCode());
        assertEquals("id-alice", r.subject().subjectId());
    }

    @Test
    void 系统任务主体按ADMIN语义() {
        var r = resolver.resolve(ExecutionContext.system("nightly-job"));
        assertTrue(r.resolved());
        assertTrue(r.subject().system());
        assertTrue(r.subject().admin());
    }

    @Test
    void 历史apikey主体标记LEGACY_ADMIN() {
        ExecutionContext ctx = ExecutionContext
                .builder(SubjectType.SERVICE, "apikey:ci", ExecutionSource.OPEN_API).build();
        var r = resolver.resolve(ctx);
        assertTrue(r.resolved());
        assertEquals(SubjectResolver.LEGACY_KEY_ROLE_CODE, r.subject().roleCode());
        assertEquals(SubjectType.SERVICE, r.subject().subjectType());
    }

    @Test
    void 未登记集成主体拒绝() {
        ExecutionContext ctx = ExecutionContext
                .builder(SubjectType.SERVICE, "some-service", ExecutionSource.OPEN_API).build();
        var r = resolver.resolve(ctx);
        assertFalse(r.resolved());
        assertEquals(ReasonCodes.SUBJECT_UNKNOWN, r.denyReason());
    }
}
