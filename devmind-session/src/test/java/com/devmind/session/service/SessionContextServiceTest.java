package com.devmind.session.service;

import com.devmind.common.agent.ChatContextLookup;
import com.devmind.common.agent.ChatContextPreparer;
import com.devmind.common.agent.exec.ContextContribution;
import com.devmind.common.agent.exec.ContextManifest;
import com.devmind.common.agent.exec.ContextPackage;
import com.devmind.common.agent.exec.ContextPackages;
import com.devmind.common.agent.exec.ContextProvider;
import com.devmind.common.agent.exec.ManifestItem;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.session.model.SessionEntity;
import com.devmind.session.model.SessionScenarioEntity;
import com.devmind.session.repo.SessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SessionContextService}（CAP-33）：装配/manifest 摘要/缓存命中/sessions 表重建/
 * chat 经 ChatContextLookup 重建（无 Spring，手工 fake；装配细节见 {@link ContextAssemblerTest}）。
 */
class SessionContextServiceTest {

    private static final ContextContribution ONE_ENTRY = new ContextContribution(
            List.of("\n---\n\n## 通用经验（global）\n\n### 条目一\n\n内容\n"),
            List.of(), List.of(), "{}",
            List.of(new ManifestItem(ManifestItem.KIND_KNOWLEDGE, "1", "条目一", "global",
                    ManifestItem.SOURCE_PROJECT_AUTO, null)));

    private static ContextProvider stub(ContextContribution c) {
        return req -> c;
    }

    @Test
    void 装配缓存与manifest摘要一致() {
        SessionContextService svc = newSvc(repoWith(null), null, null, stub(ONE_ENTRY));
        SessionContextService.Prepared p = svc.prepare("s1", null, null, "任务", null, null, null);
        assertEquals(1, p.manifest().entries());
        assertTrue(p.snapshotJson().contains("\"scenarioCode\":null") || p.snapshotJson().contains("scenarioCode"));

        Optional<ContextPackage> pkg = svc.find("s1");
        assertTrue(pkg.isPresent());
        assertTrue(pkg.get().claudeMd().contains("## 通用经验（global）"));
        assertTrue(pkg.get().claudeMd().contains("## 当前任务"));
        // manifest 摘要必须与包序列化字节流一致（runner 据此校验）
        assertEquals(p.manifest().sha256(), ContextPackages.sha256Hex(ContextPackages.toJsonBytes(pkg.get())));
        assertEquals(p.manifest().totalBytes(), ContextPackages.toJsonBytes(pkg.get()).length);
    }

    @Test
    void 无命中装配为null() {
        SessionContextService svc = newSvc(repoWith(null), null, null, stub(ContextContribution.empty()));
        assertNull(svc.prepare("s1", null, null, "任务", null, null, null));
        assertTrue(svc.find("unknown").isEmpty());
    }

    @Test
    void 缓存未命中按sessions表重建() {
        SessionEntity ent = new SessionEntity();
        ent.setId("s2");
        ent.setTaskSpec("任务");
        SessionContextService svc = newSvc(repoWith(ent), null, null, stub(ONE_ENTRY));
        Optional<ContextPackage> pkg = svc.find("s2");
        assertTrue(pkg.isPresent());
        assertTrue(pkg.get().claudeMd().contains("## 通用经验（global）"));
    }

    @Test
    void chat重建走ChatContextLookup并按场景重渲染() {
        SessionScenarioEntity scenario = new SessionScenarioEntity();
        scenario.setCode("qa");
        scenario.setName("问答场景");
        scenario.setScope(ScenarioService.SCOPE_GLOBAL);
        scenario.setPromptSkeleton("问：{{task}}");
        scenario.setExtraContextMd("口径：中文回答");
        SessionContextService svc = newSvc(repoWith(null),
                chatId -> Optional.of(new ChatContextLookup.ChatContextInfo("qa", "你好")),
                scenariosWith(scenario), stub(ONE_ENTRY));

        Optional<ContextPackage> pkg = svc.find("c1");
        assertTrue(pkg.isPresent());
        assertTrue(pkg.get().claudeMd().contains("## 场景背景"));
        assertTrue(pkg.get().claudeMd().contains("口径：中文回答"));
        assertTrue(pkg.get().claudeMd().contains("问：你好"));
    }

    @Test
    void chat场景预设与渲染prompt() {
        SessionScenarioEntity scenario = new SessionScenarioEntity();
        scenario.setCode("qa");
        scenario.setScope(ScenarioService.SCOPE_GLOBAL);
        scenario.setPromptSkeleton("问：{{task}}");
        scenario.setModel("claude-opus");
        scenario.setPermissionMode("plan");
        scenario.setAgentNodeId("node-9");
        SessionContextService svc = newSvc(repoWith(null), null, scenariosWith(scenario), stub(ONE_ENTRY));

        ChatContextPreparer.ScenarioPreset preset = svc.preset("qa");
        assertEquals("claude-opus", preset.model());
        assertEquals("plan", preset.permissionMode());
        assertEquals("node-9", preset.agentNodeId());
        assertNull(preset.projectId()); // GLOBAL 场景无上下文项目

        ChatContextPreparer.PreparedContext pc = svc.prepare("c9", "qa", "你好");
        assertEquals("问：你好", pc.renderedPrompt());
        assertEquals(1, pc.manifest().entries());
    }

    // ---------------- fake 工具 ----------------

    private static SessionContextService newSvc(SessionRepository repo, ChatContextLookup lookup,
                                                ScenarioService scenarios, ContextProvider... providers) {
        ContextAssembler assembler = new ContextAssembler(objectProvider(providers),
                JsonMapper.builder().build());
        return new SessionContextService(assembler,
                scenarios != null ? scenarios : scenariosWith(null),
                repo, null, null,
                objectProvider(lookup == null ? new ChatContextLookup[0] : new ChatContextLookup[]{lookup}));
    }

    private static ScenarioService scenariosWith(SessionScenarioEntity s) {
        return new ScenarioService(null, null, null, JsonMapper.builder().build(), null) {
            @Override
            public SessionScenarioEntity requireByCode(String code) {
                if (s != null && s.getCode().equals(code)) {
                    return s;
                }
                throw new DevMindException(ErrorCode.NOT_FOUND, "场景不存在: " + code);
            }
        };
    }

    @SafeVarargs
    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> objectProvider(T... beans) {
        return (ObjectProvider<T>) Proxy.newProxyInstance(
                SessionContextServiceTest.class.getClassLoader(),
                new Class<?>[]{ObjectProvider.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "orderedStream" -> Stream.of(beans);
                    case "getIfAvailable" -> beans.length > 0 ? beans[0] : null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** findById 返回给定实体的 SessionRepository 代理（其余方法不支持）。 */
    private static SessionRepository repoWith(SessionEntity ent) {
        return (SessionRepository) Proxy.newProxyInstance(
                SessionContextServiceTest.class.getClassLoader(),
                new Class<?>[]{SessionRepository.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("findById")) {
                        return Optional.ofNullable(ent);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
