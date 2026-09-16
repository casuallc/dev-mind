package com.devmind.project;

import com.devmind.project.config.ProjectProperties;
import com.devmind.project.config.WorktreeProperties;
import com.devmind.project.dto.ProjectView;
import com.devmind.project.model.ProjectEntity;
import com.devmind.project.repo.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GET /api/projects 的 kind 过滤单测（无 Spring 上下文，JDK 动态代理 fake 仓储）：
 * 缺省排除 WORKLOG 空间（工作日志是会话调度载体，不进项目列表/切换语义）；
 * kind=ALL 不过滤（后台管理/currentId 校验）；kind=WORKLOG 精确匹配。
 */
class ProjectServiceListKindTest {

    private final List<ProjectEntity> store = new ArrayList<>();
    private ProjectService service;

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> iface, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, handler);
    }

    @BeforeEach
    void setUp() {
        store.clear();
        store.add(entity("p1", null, Instant.parse("2026-09-01T00:00:00Z"))); // 存量行 kind=null → getter 兜底 NORMAL
        store.add(entity("p2", ProjectEntity.KIND_NORMAL, Instant.parse("2026-09-02T00:00:00Z")));
        store.add(entity("wl-alice", ProjectEntity.KIND_WORKLOG, Instant.parse("2026-09-03T00:00:00Z")));

        ProjectRepository repo = proxy(ProjectRepository.class, (p, m, args) -> switch (m.getName()) {
            case "findAllByOrderByCreatedAtDesc" -> store.stream()
                    .sorted(Comparator.comparing(ProjectEntity::getCreatedAt).reversed()).toList();
            case "findByStatusOrderByCreatedAtDesc" -> store.stream()
                    .filter(e -> e.getStatus().equals(args[0]))
                    .sorted(Comparator.comparing(ProjectEntity::getCreatedAt).reversed()).toList();
            default -> throw new UnsupportedOperationException(m.getName());
        });
        service = new ProjectService(null, new ProjectProperties(), new WorktreeProperties(),
                repo, null, null, null, null,
                null, null, null, null, null, null, null);
    }

    private static ProjectEntity entity(String id, String kind, Instant createdAt) {
        ProjectEntity e = new ProjectEntity();
        e.setId(id);
        e.setName(id);
        e.setKind(kind);
        e.setStatus("ACTIVE");
        e.setCreatedAt(createdAt);
        e.setUpdatedAt(createdAt);
        return e;
    }

    private static List<String> ids(List<ProjectView> views) {
        return views.stream().map(ProjectView::id).toList();
    }

    @Test
    void defaultExcludesWorklogSpaces() {
        assertEquals(List.of("p2", "p1"), ids(service.list(null, null)));
        assertEquals(List.of("p2", "p1"), ids(service.list(null, "")));
        // status 过滤与 kind 缺省叠加同样排除
        assertEquals(List.of("p2", "p1"), ids(service.list("ACTIVE", null)));
    }

    @Test
    void kindAllKeepsWorklogSpaces() {
        assertEquals(List.of("wl-alice", "p2", "p1"), ids(service.list(null, "ALL")));
        // status=ALL（大小写不敏感）不带 kind 时仍按缺省排除
        assertEquals(List.of("p2", "p1"), ids(service.list("all", null)));
    }

    @Test
    void kindWorklogMatchesOnlyWorklog() {
        List<ProjectView> views = service.list(null, "WORKLOG");
        assertEquals(List.of("wl-alice"), ids(views));
        assertTrue(views.stream().allMatch(v -> ProjectEntity.KIND_WORKLOG.equals(v.kind())));
    }

    @Test
    void nullKindLegacyRowTreatedAsNormal() {
        // p1 的 kind 列为 null（存量行），缺省过滤下应作为 NORMAL 保留
        assertTrue(ids(service.list(null, null)).contains("p1"));
        assertTrue(ids(service.list(null, "NORMAL")).contains("p1"));
    }
}
