package com.devmind.project;

import com.devmind.common.agent.OneShotAgentRunner;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.project.dto.AiExpandRequest;
import com.devmind.project.dto.AiExpandResponse;
import com.devmind.project.model.ProjectEntity;
import com.devmind.project.repo.ProjectRepository;
import com.devmind.project.service.RequirementAiExpandService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-105 需求 AI 扩展单测（无 Spring 上下文）：prompt 组装、契约 JSON 解析容错、
 * 执行体未装配 409、项目不存在 404。
 */
class RequirementAiExpandServiceTest {

    private static final String PROJECT_ID = "p1";

    private ProjectRepository repoReturning(ProjectEntity project) {
        return (ProjectRepository) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ProjectRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findById" -> Optional.ofNullable(project);
                    case "toString" -> "ProjectRepositoryStub";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<OneShotAgentRunner> providerOf(OneShotAgentRunner runner) {
        return (ObjectProvider<OneShotAgentRunner>) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ObjectProvider.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getIfAvailable" -> runner;
                    case "toString" -> "ObjectProviderStub";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private ProjectEntity project() {
        ProjectEntity p = new ProjectEntity();
        p.setId(PROJECT_ID);
        p.setName("演示项目");
        return p;
    }

    private RequirementAiExpandService service(OneShotAgentRunner runner) {
        return new RequirementAiExpandService(repoReturning(project()), providerOf(runner), 120);
    }

    @Test
    void promptCarriesProjectPointsAndDraftContext() {
        AtomicReference<String> seenPrompt = new AtomicReference<>();
        AtomicReference<Integer> seenTimeout = new AtomicReference<>();
        OneShotAgentRunner runner = (prompt, timeoutSeconds) -> {
            seenPrompt.set(prompt);
            seenTimeout.set(timeoutSeconds);
            return new OneShotAgentRunner.Result("s1", "{\"title\":\"t\",\"description\":\"d\"}");
        };
        AiExpandResponse resp = service(runner).expand(PROJECT_ID,
                new AiExpandRequest("支持扫码登录\n pc 端也要", "旧标题", "旧描述"));
        assertEquals("t", resp.title());
        assertEquals("s1", resp.sessionId());
        assertEquals(120, seenTimeout.get());
        String prompt = seenPrompt.get();
        assertTrue(prompt.contains("演示项目"), "prompt 应含项目名");
        assertTrue(prompt.contains("支持扫码登录"), "prompt 应含关键点");
        assertTrue(prompt.contains("旧标题"), "prompt 应含表单现有标题");
        assertTrue(prompt.contains("旧描述"), "prompt 应含表单现有描述");
        assertTrue(prompt.contains("FEATURE / BUG / IMPROVEMENT / TASK"), "prompt 应含类型词表");
    }

    @Test
    void promptOmitsBlankDraftContext() {
        AtomicReference<String> seenPrompt = new AtomicReference<>();
        OneShotAgentRunner runner = (prompt, t) -> {
            seenPrompt.set(prompt);
            return new OneShotAgentRunner.Result("s1", "{\"title\":\"t\",\"description\":\"d\"}");
        };
        service(runner).expand(PROJECT_ID, new AiExpandRequest("做个导出功能", " ", null));
        assertTrue(!seenPrompt.get().contains("用户当前标题"), "空标题不应进 prompt");
        assertTrue(!seenPrompt.get().contains("用户当前描述"), "空描述不应进 prompt");
    }

    @Test
    void parsesRawJson() {
        AiExpandResponse resp = service((p, t) -> new OneShotAgentRunner.Result("s1",
                "{\"title\":\"扫码登录\",\"type\":\"BUG\",\"description\":\"## 背景与目标\\n...\"}"))
                .expand(PROJECT_ID, new AiExpandRequest("x", null, null));
        assertEquals("扫码登录", resp.title());
        assertEquals("BUG", resp.type());
        assertEquals("## 背景与目标\n...", resp.description());
    }

    @Test
    void parsesFencedJsonWithProse() {
        String summary = "好的，结果如下：\n```json\n{\"title\":\"t\",\"type\":\"feature\",\"description\":\"d\"}\n```\n以上。";
        AiExpandResponse resp = service((p, t) -> new OneShotAgentRunner.Result("s1", summary))
                .expand(PROJECT_ID, new AiExpandRequest("x", null, null));
        assertEquals("t", resp.title());
        assertEquals("FEATURE", resp.type(), "type 应归一为大写词表值");
    }

    @Test
    void unknownTypeFallsBackToNull() {
        AiExpandResponse resp = service((p, t) -> new OneShotAgentRunner.Result("s1",
                "{\"title\":\"t\",\"type\":\"EPIC\",\"description\":\"d\"}"))
                .expand(PROJECT_ID, new AiExpandRequest("x", null, null));
        assertNull(resp.type(), "词表外类型应置 null，由前端保留原值");
    }

    @Test
    void overlongTitleIsTruncated() {
        String longTitle = "标".repeat(300);
        AiExpandResponse resp = service((p, t) -> new OneShotAgentRunner.Result("s1",
                "{\"title\":\"" + longTitle + "\",\"description\":\"d\"}"))
                .expand(PROJECT_ID, new AiExpandRequest("x", null, null));
        assertEquals(256, resp.title().length());
    }

    @Test
    void bracesInsideStringsDoNotBreakExtraction() {
        String json = "{\"title\":\"a } 括号 { 测试\",\"description\":\"d \\\"引号\\\" 完\"}";
        assertEquals(json, RequirementAiExpandService.extractJsonObject("前言 " + json + " 后语"));
        assertNull(RequirementAiExpandService.extractJsonObject("没有对象"));
        assertNull(RequirementAiExpandService.extractJsonObject(null));
    }

    @Test
    void nonJsonSummaryFailsVisibly() {
        DevMindException e = assertThrows(DevMindException.class, () -> service((p, t) ->
                new OneShotAgentRunner.Result("s1", "任务完成（fake，stdin EOF）。"))
                .expand(PROJECT_ID, new AiExpandRequest("x", null, null)));
        assertEquals(ErrorCode.INTERNAL, e.getErrorCode());
        assertTrue(e.getMessage().contains("s1"), "错误应带 sessionId 便于回溯");
    }

    @Test
    void missingFieldsFailVisibly() {
        DevMindException e = assertThrows(DevMindException.class, () -> service((p, t) ->
                new OneShotAgentRunner.Result("s1", "{\"title\":\"只有标题\"}"))
                .expand(PROJECT_ID, new AiExpandRequest("x", null, null)));
        assertEquals(ErrorCode.INTERNAL, e.getErrorCode());
    }

    @Test
    void unavailableRunnerIsConflict() {
        RequirementAiExpandService svc = new RequirementAiExpandService(
                repoReturning(project()), providerOf(null), 120);
        DevMindException e = assertThrows(DevMindException.class,
                () -> svc.expand(PROJECT_ID, new AiExpandRequest("x", null, null)));
        assertEquals(ErrorCode.CONFLICT, e.getErrorCode());
    }

    @Test
    void missingProjectIsNotFound() {
        RequirementAiExpandService svc = new RequirementAiExpandService(
                repoReturning(null), providerOf((p, t) -> null), 120);
        DevMindException e = assertThrows(DevMindException.class,
                () -> svc.expand("ghost", new AiExpandRequest("x", null, null)));
        assertEquals(ErrorCode.NOT_FOUND, e.getErrorCode());
    }
}
