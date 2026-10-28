package com.devmind.flow.verify;

import com.devmind.common.agent.exec.ContextAssemblyRequest;
import com.devmind.common.agent.exec.ContextContribution;
import com.devmind.common.agent.exec.ManifestItem;
import com.devmind.common.verify.VerifyTokenService;
import com.devmind.project.ProjectService;
import com.devmind.project.RequirementService;
import com.devmind.project.dto.ProjectView;
import com.devmind.project.model.RequirementEntity;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-73 FR-04 VerifyContextProvider 单测（无 Spring）：绑定+开关+serverUrl 三件套齐才物化
 * 助手脚本；dryRun/无 sessionId/瘦上下文外的跳过口径；token 绑会话+需求可回验；
 * 脚本内嵌 trigger URL 与参数解析骨架。
 */
class VerifyContextProviderTest {

    private static final String SECRET = "test-secret-0123456789abcdef";

    private RequirementEntity requirement;
    private boolean projectEnabled = true;
    private String serverUrl = "https://devmind.example.com";

    private final VerifyProperties props = new VerifyProperties();
    private final VerifyTokenService tokenService = new VerifyTokenService(SECRET);

    private VerifyContextProvider newProvider() {
        props.setServerUrl(serverUrl);
        return new VerifyContextProvider(new FakeRequirementService(), new FakeProjectService(),
                props, tokenService);
    }

    @Test
    void materializesHelperWhenBoundAndEnabled() {
        requirement = entity(7L);
        ContextContribution c = newProvider().contribute(req("s1", "r1", false));
        assertEquals(2, c.inputs().size());
        assertEquals(VerifyContextProvider.SCRIPT_PATH, c.inputs().get(0).path());
        assertEquals(VerifyContextProvider.SCRIPT_PATH_BAT, c.inputs().get(1).path());
        assertEquals(1, c.items().size());
        assertEquals(ManifestItem.KIND_TOOL, c.items().get(0).kind());

        String sh = decode(c.inputs().get(0).base64());
        assertTrue(sh.contains("/api/verify/trigger?token=" + VerifyTokenService.PREFIX), sh);
        assertTrue(sh.startsWith("#!/bin/sh"), sh);
        assertTrue(sh.contains("--modules"), sh);
        assertFalse(sh.contains(serverUrl + "//api"), "基址尾斜杠已剥，不得出现双斜杠");
        assertTrue(sh.contains(serverUrl + "/api/verify/trigger"), sh);
        String bat = decode(c.inputs().get(1).base64());
        assertTrue(bat.contains("/api/verify/trigger?token="), bat);

        // token 回验：绑的是本会话+本需求
        String token = sh.split("token=")[1].split("'")[0];
        VerifyTokenService.Payload p = tokenService.verify(token).orElseThrow();
        assertEquals("s1", p.sessionId());
        assertEquals("r1", p.requirementId());

        String section = c.claudeMdSections().get(0);
        assertTrue(section.contains("## 开发中验证"), section);
        assertTrue(section.contains(".devmind/input/" + VerifyContextProvider.SCRIPT_PATH), section);
    }

    @Test
    void skipsWhenUnboundDisabledDryRunOrNoSession() {
        // 未绑定验证环境
        requirement = entity(null);
        assertTrue(newProvider().contribute(req("s1", "r1", false)).inputs().isEmpty());
        // 项目开关关闭
        requirement = entity(7L);
        projectEnabled = false;
        assertTrue(newProvider().contribute(req("s1", "r1", false)).inputs().isEmpty());
        projectEnabled = true;
        // dryRun 预览不签发 token
        assertTrue(newProvider().contribute(
                new ContextAssemblyRequest("p1", List.of(), List.of(), List.of(), List.of(),
                        List.of(), List.of(), null, true, true, "r1", List.of(), "s1", false))
                .inputs().isEmpty());
        // 无 sessionId（chat/兼容路径）
        assertTrue(newProvider().contribute(req(null, "r1", false)).inputs().isEmpty());
        // 无需求
        assertTrue(newProvider().contribute(req("s1", null, false)).inputs().isEmpty());
        // serverUrl 未配置
        serverUrl = " ";
        assertTrue(newProvider().contribute(req("s1", "r1", false)).inputs().isEmpty());
    }

    @Test
    void leanSessionsStillGetHelper() {
        // CAP-52 瘦上下文会话也是验证链受用者：lean 不豁免本 provider
        requirement = entity(7L);
        ContextContribution c = newProvider().contribute(req("s9", "r1", true));
        assertEquals(2, c.inputs().size());
    }

    // ---------------- fakes ----------------

    private static ContextAssemblyRequest req(String sessionId, String requirementId, boolean lean) {
        return new ContextAssemblyRequest("p1", List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), null, true, false, requirementId, List.of(),
                sessionId, lean);
    }

    private static RequirementEntity entity(Long verifyEnvId) {
        RequirementEntity e = new RequirementEntity();
        e.setId("r1");
        e.setProjectId("p1");
        e.setVerifyEnvironmentId(verifyEnvId);
        return e;
    }

    private static String decode(String b64) {
        return new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
    }

    private class FakeRequirementService extends RequirementService {
        FakeRequirementService() {
            super(null, null, null, null, null, null, null, null, null, null);
        }

        @Override
        public RequirementEntity requireById(String id) {
            if (requirement == null || !requirement.getId().equals(id)) {
                throw new IllegalArgumentException("需求不存在: " + id);
            }
            return requirement;
        }
    }

    private class FakeProjectService extends ProjectService {
        FakeProjectService() {
            super(null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null);
        }

        @Override
        public ProjectView get(String id) {
            return new ProjectView(id, "项目", null, null, List.of(), null, "ACTIVE", null, null,
                    null, null, false, false, null, null, null, null, "NORMAL",
                    projectEnabled, 20, false, null, null);
        }
    }
}
