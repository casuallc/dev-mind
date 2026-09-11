package com.devmind.agent.controller;

import com.devmind.agent.controller.AgentOutputController.FileItem;
import com.devmind.agent.controller.AgentOutputController.OutputUploadRequest;
import com.devmind.agent.model.AgentNodeEntity;
import com.devmind.agent.service.AgentNodeService;
import com.devmind.common.agent.SessionOutputSink;
import com.devmind.common.exception.DevMindException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** {@link AgentOutputController}：token 认证 + 限制校验 + SPI 落库委托。 */
class AgentOutputControllerTest {

    private final AgentNodeService nodeService = mock(AgentNodeService.class);

    @Test
    void rejectsInvalidToken() {
        when(nodeService.resolveByToken("bad")).thenReturn(Optional.empty());
        AgentOutputController ctl = new AgentOutputController(nodeService, providerOf(null));
        DevMindException e = assertThrows(DevMindException.class,
                () -> ctl.upload("s1", "bad", new OutputUploadRequest(List.of())));
        assertEquals(401, e.getErrorCode().getStatus());
    }

    @Test
    void storesFilesForValidToken() {
        when(nodeService.resolveByToken("tok")).thenReturn(Optional.of(new AgentNodeEntity()));
        List<SessionOutputSink.OutputFile> captured = new ArrayList<>();
        SessionOutputSink sink = (sessionId, files) -> {
            assertEquals("s1", sessionId);
            captured.addAll(files);
        };
        AgentOutputController ctl = new AgentOutputController(nodeService, providerOf(sink));
        ctl.upload("s1", "tok", new OutputUploadRequest(List.of(
                new FileItem("analysis.md", "# 分析"), new FileItem("wi-plan.json", "[]"))));
        assertEquals(2, captured.size());
        assertEquals("analysis.md", captured.get(0).fileName());
        assertEquals("# 分析", captured.get(0).content());
    }

    @Test
    void rejectsOversizedOrBadName() {
        when(nodeService.resolveByToken("tok")).thenReturn(Optional.of(new AgentNodeEntity()));
        AgentOutputController ctl = new AgentOutputController(nodeService,
                providerOf((s, f) -> { }));
        // 文件名含路径分隔符 → 400
        DevMindException e1 = assertThrows(DevMindException.class,
                () -> ctl.upload("s1", "tok", new OutputUploadRequest(
                        List.of(new FileItem("../evil.md", "x")))));
        assertEquals(400, e1.getErrorCode().getStatus());
        // 单文件超限 → 400
        DevMindException e2 = assertThrows(DevMindException.class,
                () -> ctl.upload("s1", "tok", new OutputUploadRequest(List.of(
                        new FileItem("big.md", "x".repeat(AgentOutputController.MAX_FILE_CHARS + 1))))));
        assertEquals(400, e2.getErrorCode().getStatus());
        // 文件数超限 → 400
        List<FileItem> many = new ArrayList<>();
        for (int i = 0; i < AgentOutputController.MAX_FILES + 1; i++) {
            many.add(new FileItem("f" + i + ".md", "x"));
        }
        DevMindException e3 = assertThrows(DevMindException.class,
                () -> ctl.upload("s1", "tok", new OutputUploadRequest(many)));
        assertEquals(400, e3.getErrorCode().getStatus());
        assertTrue(true);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<SessionOutputSink> providerOf(SessionOutputSink s) {
        ObjectProvider<SessionOutputSink> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(s);
        return provider;
    }
}
