package com.devmind.project.authz;

import com.devmind.common.authz.AuthorizationDecision;
import com.devmind.common.authz.ReasonCodes;
import com.devmind.common.authz.ResolvedSubject;
import com.devmind.common.authz.SubjectType;
import com.devmind.project.model.ProjectEntity;
import com.devmind.project.repo.ProjectMemberRepository;
import com.devmind.project.repo.ProjectRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-62 / CAP-108 M1 项目读写判定矩阵：WORKLOG 归属 / PUBLIC / PRIVATE × 读/写 × 成员/非成员/ADMIN/SYSTEM。
 * 无 Spring 上下文，repository 用 JDK 动态代理内存 fake。
 */
class ProjectAccessTest {

    private final Map<String, ProjectEntity> projects = new HashMap<>();
    private final Set<String> members = new HashSet<>(); // projectId + "@" + username

    private final ProjectRepository projectRepo = (ProjectRepository) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{ProjectRepository.class},
            (p, m, args) -> switch (m.getName()) {
                case "findById" -> Optional.ofNullable(projects.get((String) args[0]));
                default -> throw new UnsupportedOperationException(m.getName());
            });
    private final ProjectMemberRepository memberRepo = (ProjectMemberRepository) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{ProjectMemberRepository.class},
            (p, m, args) -> switch (m.getName()) {
                case "existsByProjectIdAndUserId" -> members.contains(args[0] + "@" + args[1]);
                default -> throw new UnsupportedOperationException(m.getName());
            });
    private final ProjectAccess access = new ProjectAccess(projectRepo, memberRepo);

    private static ResolvedSubject user(String username, String baseRole) {
        return new ResolvedSubject(SubjectType.USER, "id-" + username, username, baseRole, baseRole);
    }

    private static ResolvedSubject system() {
        return new ResolvedSubject(SubjectType.SYSTEM, "task", "task", ResolvedSubject.BASE_ADMIN, "SYSTEM");
    }

    private void stubProject(String id, String kind, String visibility, String owner) {
        ProjectEntity p = new ProjectEntity();
        p.setId(id);
        p.setKind(kind);
        p.setVisibility(visibility);
        p.setOwnerId(owner);
        projects.put(id, p);
    }

    @Test
    void 项目不存在统一RESOURCE_HIDDEN() {
        AuthorizationDecision d = access.canRead(user("alice", "DEVELOPER"), "ghost");
        assertFalse(d.allowed());
        assertEquals(ReasonCodes.RESOURCE_HIDDEN, d.reasonCode());
        d = access.canWrite(user("alice", "DEVELOPER"), "ghost");
        assertEquals(ReasonCodes.RESOURCE_HIDDEN, d.reasonCode());
    }

    @Test
    void SYSTEM与ADMIN读写均放行() {
        stubProject("p1", ProjectEntity.KIND_NORMAL, ProjectEntity.VISIBILITY_PRIVATE, "bob");
        assertTrue(access.canRead(system(), "p1").allowed());
        assertTrue(access.canWrite(system(), "p1").allowed());
        ResolvedSubject admin = user("root", ResolvedSubject.BASE_ADMIN);
        assertTrue(access.canRead(admin, "p1").allowed());
        assertTrue(access.canWrite(admin, "p1").allowed());
    }

    @Test
    void WORKLOG仅归属可读他人404() {
        stubProject("wl", ProjectEntity.KIND_WORKLOG, ProjectEntity.VISIBILITY_PRIVATE, "alice");
        assertTrue(access.canRead(user("alice", "DEVELOPER"), "wl").allowed());
        assertTrue(access.canWrite(user("alice", "DEVELOPER"), "wl").allowed());
        AuthorizationDecision d = access.canRead(user("bob", "DEVELOPER"), "wl");
        assertEquals(ReasonCodes.RESOURCE_HIDDEN, d.reasonCode());
        assertEquals(ReasonCodes.RESOURCE_HIDDEN, access.canWrite(user("bob", "DEVELOPER"), "wl").reasonCode());
    }

    @Test
    void PUBLIC全员可读_成员可写_非成员写403() {
        stubProject("pub", ProjectEntity.KIND_NORMAL, ProjectEntity.VISIBILITY_PUBLIC, "alice");
        members.add("pub@alice");
        assertTrue(access.canRead(user("bob", "VIEWER"), "pub").allowed());
        assertTrue(access.canWrite(user("alice", "DEVELOPER"), "pub").allowed());
        AuthorizationDecision d = access.canWrite(user("bob", "DEVELOPER"), "pub");
        assertFalse(d.allowed());
        assertEquals(ReasonCodes.SCOPE_DENIED, d.reasonCode());
    }

    @Test
    void PRIVATE仅成员读写_非成员404() {
        stubProject("prv", ProjectEntity.KIND_NORMAL, ProjectEntity.VISIBILITY_PRIVATE, "alice");
        members.add("prv@alice");
        assertTrue(access.canRead(user("alice", "DEVELOPER"), "prv").allowed());
        assertTrue(access.canWrite(user("alice", "DEVELOPER"), "prv").allowed());
        assertEquals(ReasonCodes.RESOURCE_HIDDEN, access.canRead(user("bob", "DEVELOPER"), "prv").reasonCode());
        assertEquals(ReasonCodes.RESOURCE_HIDDEN, access.canWrite(user("bob", "DEVELOPER"), "prv").reasonCode());
    }
}
