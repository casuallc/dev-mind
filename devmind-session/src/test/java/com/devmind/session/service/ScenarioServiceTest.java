package com.devmind.session.service;

import com.devmind.common.exception.DevMindException;
import com.devmind.project.model.Project;
import com.devmind.session.dto.ScenarioRequest;
import com.devmind.session.model.SessionScenarioEntity;
import com.devmind.session.model.SessionTemplateEntity;
import com.devmind.session.repo.SessionScenarioRepository;
import com.devmind.session.repo.SessionTemplateRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ScenarioService} 单测（无 mockito：JDK Proxy 内存 repo + fake 事务管理器）。
 * 覆盖：骨架渲染四占位符与 null 安全；save 校验矩阵（code 必填/冲突、scope 非法、
 * PROJECT 缺 projectId、名称必填、GLOBAL 清 projectId）；模板迁移逐行 existsByCode 幂等。
 */
class ScenarioServiceTest {

    // ---------------- 内存 repo fakes ----------------

    /** 内存 scenario repo：save 分配 id（反射），支持 findByCode/existsByCode/排序列表/findById。 */
    private static SessionScenarioRepository scenarioRepo(Map<Long, SessionScenarioEntity> store) {
        AtomicLong seq = new AtomicLong(store.keySet().stream().mapToLong(Long::longValue).max().orElse(0));
        return (SessionScenarioRepository) Proxy.newProxyInstance(
                ScenarioServiceTest.class.getClassLoader(),
                new Class<?>[]{SessionScenarioRepository.class},
                (p, m, args) -> switch (m.getName()) {
                    case "save" -> {
                        SessionScenarioEntity e = (SessionScenarioEntity) args[0];
                        if (e.getId() == null) {
                            setId(e, seq.incrementAndGet());
                        }
                        store.put(e.getId(), e);
                        yield e;
                    }
                    case "findByCode" -> store.values().stream()
                            .filter(e -> e.getCode().equals(args[0])).findFirst();
                    case "existsByCode" -> store.values().stream()
                            .anyMatch(e -> e.getCode().equals(args[0]));
                    case "findAllByOrderBySortOrderAscIdAsc" -> store.values().stream()
                            .sorted(Comparator.comparingInt(SessionScenarioEntity::getSortOrder)
                                    .thenComparing(SessionScenarioEntity::getId))
                            .toList();
                    case "findById" -> Optional.ofNullable(store.get(args[0]));
                    case "deleteById" -> {
                        store.remove(args[0]);
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    private static void setId(SessionScenarioEntity e, long id) {
        try {
            Field f = SessionScenarioEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(e, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static SessionTemplateRepository templateRepo(List<SessionTemplateEntity> rows) {
        return (SessionTemplateRepository) Proxy.newProxyInstance(
                ScenarioServiceTest.class.getClassLoader(),
                new Class<?>[]{SessionTemplateRepository.class},
                (p, m, args) -> switch (m.getName()) {
                    case "findAll" -> rows;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    /** fake 事务管理器：getTransaction 返回空状态，commit/rollback 空操作。 */
    private static PlatformTransactionManager fakeTxManager() {
        return (PlatformTransactionManager) Proxy.newProxyInstance(
                ScenarioServiceTest.class.getClassLoader(),
                new Class<?>[]{PlatformTransactionManager.class},
                (p, m, args) -> switch (m.getName()) {
                    case "getTransaction" -> new SimpleTransactionStatus();
                    case "commit", "rollback" -> null;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    private static ScenarioService service(Map<Long, SessionScenarioEntity> store,
                                           List<SessionTemplateEntity> templates) {
        return new ScenarioService(scenarioRepo(store), templateRepo(templates), null,
                JsonMapper.builder().build(), fakeTxManager());
    }

    private static ScenarioRequest req(String code, String name, String scope, String projectId) {
        return new ScenarioRequest(code, name, null, null, null, null, null, null, null, null, null,
                scope, projectId, null, null);
    }

    // ---------------- 渲染 ----------------

    @Test
    void 渲染四占位符与null安全() {
        ScenarioService svc = service(new LinkedHashMap<>(), List.of());
        SessionScenarioEntity s = new SessionScenarioEntity();
        s.setPromptSkeleton("任务:{{task}}|项目:{{project}}|分支:{{branch}}|需求:{{requirement}}");
        Project project = new Project("p1", "商城", null, "main", List.of(), null);
        assertEquals("任务:修bug|项目:商城|分支:main|需求:登录改造",
                svc.render(s, "修bug", project, "登录改造"));
        // null 安全：task/project/requirement 全空 → 占位符渲染为空串
        assertEquals("任务:|项目:|分支:|需求:", svc.render(s, null, null, null));
        // 骨架为 null → 空串
        SessionScenarioEntity empty = new SessionScenarioEntity();
        assertEquals("", svc.render(empty, "x", project, null));
    }

    // ---------------- save 校验 ----------------

    @Test
    void 保存校验矩阵() {
        Map<Long, SessionScenarioEntity> store = new LinkedHashMap<>();
        ScenarioService svc = service(store, List.of());

        // code 必填
        assertThrows(DevMindException.class, () -> svc.save(null, req("  ", "n", null, null)));
        // scope 非法
        assertThrows(DevMindException.class, () -> svc.save(null, req("a", "n", "TEAM", null)));
        // PROJECT 缺 projectId
        assertThrows(DevMindException.class, () -> svc.save(null, req("a", "n", "PROJECT", null)));
        // 名称必填
        assertThrows(DevMindException.class, () -> svc.save(null, req("a", null, "GLOBAL", null)));

        // 正常建 GLOBAL：projectId 即使有值也清空
        var view = svc.save(null, req("code-a", "场景A", "GLOBAL", "p-should-clear"));
        assertEquals("code-a", view.code());
        assertNull(view.projectId());

        // code 冲突
        assertThrows(DevMindException.class, () -> svc.save(null, req("code-a", "n2", null, null)));
        // 更新改名为已存在 code → 冲突
        svc.save(null, req("code-b", "场景B", null, null));
        SessionScenarioEntity b = store.values().stream()
                .filter(e -> e.getCode().equals("code-b")).findFirst().orElseThrow();
        assertThrows(DevMindException.class, () -> svc.save(b.getId(), req("code-a", "场景B", null, null)));
    }

    // ---------------- 迁移幂等 ----------------

    @Test
    void 迁移幂等_逐行existsByCode跳过() {
        Map<Long, SessionScenarioEntity> store = new LinkedHashMap<>();
        ScenarioService svc = service(store,
                List.of(template("tpl-1", "模板一"), template("tpl-2", "模板二")));
        svc.migrateFromTemplates();
        assertEquals(2, store.size());
        assertEquals("骨架:tpl-1", store.values().stream()
                .filter(e -> e.getCode().equals("tpl-1")).findFirst().orElseThrow().getPromptSkeleton());

        // 二次迁移零新增（幂等）
        svc.migrateFromTemplates();
        assertEquals(2, store.size());
    }

    private static SessionTemplateEntity template(String code, String name) {
        SessionTemplateEntity t = new SessionTemplateEntity();
        t.setCode(code);
        t.setName(name);
        t.setPrompt("骨架:" + code);
        t.setEnabled(true);
        t.setSortOrder(1);
        return t;
    }

    @Test
    void 迁移跳过已存在code不覆盖() {
        Map<Long, SessionScenarioEntity> store = new LinkedHashMap<>();
        // 预存同 code 场景（模拟部分迁移/手工预建）→ 应跳过不覆盖
        SessionScenarioEntity pre = new SessionScenarioEntity();
        pre.setCode("tpl-1");
        pre.setName("手工预建");
        pre.setScope("GLOBAL");
        store.put(1L, withId(pre, 1L));

        ScenarioService svc = service(store, List.of(template("tpl-1", "模板一"), template("tpl-2", "模板二")));
        svc.migrateFromTemplates();
        assertEquals(2, store.size());
        assertEquals("手工预建", store.get(1L).getName()); // 未覆盖
        assertTrue(store.values().stream().anyMatch(e -> e.getCode().equals("tpl-2")));
    }

    private static SessionScenarioEntity withId(SessionScenarioEntity e, long id) {
        setId(e, id);
        return e;
    }
}
