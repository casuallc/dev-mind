package com.devmind.test.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.project.model.Project;
import com.devmind.project.ProjectService;
import com.devmind.test.dto.ScriptSuiteEnv;
import com.devmind.test.dto.ScriptSuiteRequest;
import com.devmind.test.dto.ScriptSuiteView;
import com.devmind.test.model.TestSuiteEntity;
import com.devmind.test.repo.TestSuiteRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CAP-69 脚本套件 CRUD 的关键语义：secret env 视图层恒掩码、PUT 掩码回传 = 该条不变
 * （对齐 CAP-68），路径类字段白名单（junitPath 会被拼进 shell 尾段，穿越形态必须拒掉）。
 */
class ScriptSuiteServiceTest {

    private TestSuiteRepository suiteRepo;
    private ProjectService projectService;
    private ScriptSuiteService service;

    @BeforeEach
    void setUp() {
        suiteRepo = mock(TestSuiteRepository.class);
        projectService = mock(ProjectService.class);
        // 绑定项目校验默认放行（个别用例自行覆盖抛 404）
        Mockito.lenient().when(projectService.requireProject(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(mock(Project.class));
        when(suiteRepo.save(any())).thenAnswer(inv -> {
            TestSuiteEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(1L);
            }
            return e;
        });
        service = new ScriptSuiteService(suiteRepo, projectService, new ObjectMapper());
    }

    private ScriptSuiteRequest req(List<ScriptSuiteEnv> env) {
        return new ScriptSuiteRequest("p1", "admq e2e", "http://git.local/admq.git", "master", "e2e",
                "npm ci && npx playwright test", "test-results/junit.xml", env, null, null, null);
    }

    @Test
    void secretEnvIsMaskedInView() {
        ScriptSuiteView v = service.create(req(List.of(
                new ScriptSuiteEnv("BASE_URL", "http://x", false),
                new ScriptSuiteEnv("PASSWORD", "s3cret", true))));
        assertEquals("http://x", v.env().get(0).value());
        assertEquals(ScriptSuiteEnv.MASK, v.env().get(1).value());
        assertTrue(v.env().get(1).isSecret());
    }

    @Test
    void maskedEchoOnUpdateKeepsOriginalValue() {
        service.create(req(List.of(new ScriptSuiteEnv("PASSWORD", "s3cret", true))));
        TestSuiteEntity saved = Mockito.mockingDetails(suiteRepo).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("save"))
                .map(i -> (TestSuiteEntity) i.getArgument(0))
                .findFirst().orElseThrow();
        when(suiteRepo.findById(1L)).thenReturn(Optional.of(saved));

        // 掩码原样回传 + 新增一条明文 secret + 改一条普通值
        ScriptSuiteView v = service.update(1L, req(List.of(
                new ScriptSuiteEnv("PASSWORD", ScriptSuiteEnv.MASK, true),
                new ScriptSuiteEnv("TOKEN", "tok-2", true),
                new ScriptSuiteEnv("BASE_URL", "http://y", false))));

        assertEquals(ScriptSuiteEnv.MASK, v.env().get(0).value());
        // 库里必须还是原值（视图掩码 ≠ 落库掩码）
        List<ScriptSuiteEnv> stored = service.envOf(saved);
        assertEquals("s3cret", stored.get(0).value());
        assertEquals("tok-2", stored.get(1).value());
        assertEquals("http://y", stored.get(2).value());
    }

    @Test
    void rejectsPathTraversalAndBadWorkspaceKey() {
        assertThrows(DevMindException.class,
                () -> service.create(new ScriptSuiteRequest("p1", "n", "r", "master", null,
                        "cmd", "../etc/passwd", null, null, null, null)));
        assertThrows(DevMindException.class,
                () -> service.create(new ScriptSuiteRequest("p1", "n", "r", "master", null,
                        "cmd", "/abs/path.xml", null, null, null, null)));
        assertThrows(DevMindException.class,
                () -> service.create(new ScriptSuiteRequest("p1", "n", "r", "master", "..\\x",
                        "cmd", "out.xml", null, null, null, null)));
        assertThrows(DevMindException.class,
                () -> service.create(new ScriptSuiteRequest("p1", "n", "r", "master", null,
                        "cmd", "out.xml", null, null, null, "bad key!")));
        assertThrows(DevMindException.class,
                () -> service.create(req(List.of(new ScriptSuiteEnv("1BAD", "v", false)))));
    }

    @Test
    void projectIdIsMandatoryAndMustExist() {
        ScriptSuiteView v = service.create(req(null));
        assertEquals("p1", v.projectId());
        // 缺 projectId 直接 400（强制绑定项目）
        assertThrows(DevMindException.class, () -> service.create(new ScriptSuiteRequest(null,
                "n", "r", "master", null, "cmd", "out.xml", null, null, null, null)));
        // 项目不存在 → 404 透出
        when(projectService.requireProject("ghost")).thenThrow(
                new DevMindException(ErrorCode.NOT_FOUND, "项目不存在: ghost"));
        assertThrows(DevMindException.class, () -> service.create(new ScriptSuiteRequest("ghost",
                "n", "r", "master", null, "cmd", "out.xml", null, null, null, null)));
    }

    @Test
    void listRequiresProjectId() {
        assertThrows(DevMindException.class, () -> service.list("  "));
    }

    @Test
    void defaultsTimeoutAndRejectsNonScriptKind() {
        ScriptSuiteView v = service.create(req(null));
        assertEquals(7200, v.timeoutSec());

        TestSuiteEntity api = new TestSuiteEntity();
        api.setId(9L);
        api.setKind("api");
        when(suiteRepo.findById(9L)).thenReturn(Optional.of(api));
        assertThrows(DevMindException.class, () -> service.get(9L));
    }
}
