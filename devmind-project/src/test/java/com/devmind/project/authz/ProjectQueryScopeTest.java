package com.devmind.project.authz;

import com.devmind.auth.authz.SubjectResolver;
import com.devmind.auth.repo.RoleRepository;
import com.devmind.auth.repo.UserRepository;
import com.devmind.common.authz.ExecutionContext;
import com.devmind.common.authz.ExecutionContextHolder;
import com.devmind.common.authz.ExecutionSource;
import com.devmind.common.authz.ResolvedSubject;
import com.devmind.common.authz.SubjectType;
import com.devmind.project.model.ProjectEntity;
import com.devmind.project.repo.ProjectMemberRepository;
import com.devmind.project.repo.ProjectRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-108 §4.2.3 查询范围：ADMIN 全量；普通主体 = PUBLIC 非 WORKLOG ∪ 成员项目；
 * 无线程上下文 → null（调用方不过滤，M1 软边界）；主体不可解析 → 空集（不回退全量）。
 */
class ProjectQueryScopeTest {

    private final List<ProjectEntity> projects = new ArrayList<>();
    private final Set<String> memberProjectIds = new HashSet<>();

    private final ProjectRepository projectRepo = (ProjectRepository) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{ProjectRepository.class},
            (p, m, args) -> switch (m.getName()) {
                case "findAll" -> new ArrayList<>(projects);
                default -> throw new UnsupportedOperationException(m.getName());
            });
    private final ProjectMemberRepository memberRepo = (ProjectMemberRepository) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{ProjectMemberRepository.class},
            (p, m, args) -> switch (m.getName()) {
                case "findProjectIdsByUserId" -> new HashSet<>(memberProjectIds);
                default -> throw new UnsupportedOperationException(m.getName());
            });
    /** 真实 SubjectResolver + 空用户表 → 任何 USER 主体均不可解析 */
    private final SubjectResolver subjects = new SubjectResolver(
            (UserRepository) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{UserRepository.class},
                    (p, m, args) -> switch (m.getName()) {
                        case "findByUsername" -> Optional.empty();
                        default -> throw new UnsupportedOperationException(m.getName());
                    }),
            (RoleRepository) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{RoleRepository.class},
                    (p, m, args) -> {
                        throw new UnsupportedOperationException(m.getName());
                    }));
    private final ProjectQueryScope scope = new ProjectQueryScope(projectRepo, memberRepo, subjects);

    private static ProjectEntity project(String id, String kind, String visibility) {
        ProjectEntity p = new ProjectEntity();
        p.setId(id);
        p.setKind(kind);
        p.setVisibility(visibility);
        return p;
    }

    private static ResolvedSubject user(String username, String baseRole) {
        return new ResolvedSubject(SubjectType.USER, "id-" + username, username, baseRole, baseRole);
    }

    @AfterEach
    void tearDown() {
        ExecutionContextHolder.clear();
    }

    @Test
    void ADMIN返回全部项目() {
        projects.add(project("pub", ProjectEntity.KIND_NORMAL, ProjectEntity.VISIBILITY_PUBLIC));
        projects.add(project("prv", ProjectEntity.KIND_NORMAL, ProjectEntity.VISIBILITY_PRIVATE));
        projects.add(project("wl", ProjectEntity.KIND_WORKLOG, ProjectEntity.VISIBILITY_PRIVATE));
        assertEquals(Set.of("pub", "prv", "wl"), scope.readableProjectIds(user("root", "ADMIN")));
    }

    @Test
    void 普通用户为PUBLIC非WORKLOG并上成员项目() {
        projects.add(project("pub", ProjectEntity.KIND_NORMAL, ProjectEntity.VISIBILITY_PUBLIC));
        projects.add(project("prv", ProjectEntity.KIND_NORMAL, ProjectEntity.VISIBILITY_PRIVATE));
        projects.add(project("wl-pub", ProjectEntity.KIND_WORKLOG, ProjectEntity.VISIBILITY_PUBLIC));
        memberProjectIds.add("prv");
        memberProjectIds.add("wl-own");
        assertEquals(Set.of("pub", "prv", "wl-own"), scope.readableProjectIds(user("alice", "DEVELOPER")));
    }

    @Test
    void 无线程上下文返回null不过滤() {
        assertNull(scope.readableProjectIdsForCurrent());
    }

    @Test
    void 上下文主体不可解析返回空集() {
        try (var ignored = ExecutionContextHolder.set(
                ExecutionContext.builder(SubjectType.USER, "ghost", ExecutionSource.WEB).build())) {
            assertTrue(scope.readableProjectIdsForCurrent().isEmpty());
        }
    }
}
