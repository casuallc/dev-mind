package com.devmind.agent.controller;

import com.devmind.agent.model.AgentNodeEntity;
import com.devmind.agent.service.AgentNodeService;
import com.devmind.common.decision.DecisionLabBundleProvider;
import com.devmind.common.decision.LabBundle;
import com.devmind.common.decision.LabBundles;
import com.devmind.common.exception.DevMindException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** {@link AgentLabBundleController}：token 认证 + kind 白名单 + 包供给 + 404 语义。 */
class AgentLabBundleControllerTest {

    private static final byte[] ZIP = LabBundles.pack(
            new LabBundles.Manifest("laya_eval.py", "payload.json"),
            Map.of("laya_eval.py", "print(1)".getBytes(StandardCharsets.UTF_8)), new byte[0]);

    private final AgentNodeService nodeService = mock(AgentNodeService.class);

    @Test
    void rejectsInvalidToken() {
        when(nodeService.resolveByToken("bad")).thenReturn(Optional.empty());
        AgentLabBundleController ctl = new AgentLabBundleController(nodeService, providerOf((k, i) -> Optional.empty()));
        DevMindException e = assertThrows(DevMindException.class,
                () -> ctl.pull(DecisionLabBundleProvider.KIND_EVALUATION, "e1", "bad"));
        assertEquals(401, e.getErrorCode().getStatus());
    }

    @Test
    void rejectsUnknownKind() {
        when(nodeService.resolveByToken("tok")).thenReturn(Optional.of(new AgentNodeEntity()));
        AgentLabBundleController ctl = new AgentLabBundleController(nodeService, providerOf((k, i) -> Optional.empty()));
        // 拼错 kind 必须明说可用值：回 404 会让人以为"任务不存在"，而任务就在列表里
        DevMindException e = assertThrows(DevMindException.class, () -> ctl.pull("evaluaton", "e1", "tok"));
        assertEquals(400, e.getErrorCode().getStatus());
        assertTrue(e.getMessage().contains(DecisionLabBundleProvider.KIND_EVALUATION), e.getMessage());
        assertTrue(e.getMessage().contains(DecisionLabBundleProvider.KIND_FINETUNE), e.getMessage());
    }

    @Test
    void returnsZipBytesWithSafeFileName() {
        when(nodeService.resolveByToken("tok")).thenReturn(Optional.of(new AgentNodeEntity()));
        AgentLabBundleController ctl = new AgentLabBundleController(nodeService,
                providerOf((k, i) -> Optional.of(new LabBundle("基准集 v1.zip", ZIP))));
        ResponseEntity<byte[]> resp = ctl.pull(DecisionLabBundleProvider.KIND_EVALUATION, "e1", "tok");

        assertEquals(200, resp.getStatusCode().value());
        assertEquals("application/zip", String.valueOf(resp.getHeaders().getContentType()));
        assertEquals(ZIP.length, resp.getBody().length);
        // 中文包名进响应头前被安全化（HTTP 头是 latin-1，且名字来自实验室输入）
        String disposition = resp.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertTrue(disposition.startsWith("attachment; filename=\""), disposition);
        assertFalse(disposition.contains("基准"), disposition);
    }

    @Test
    void notFoundWhenTaskHasNoBundle() {
        when(nodeService.resolveByToken("tok")).thenReturn(Optional.of(new AgentNodeEntity()));
        AgentLabBundleController ctl = new AgentLabBundleController(nodeService,
                providerOf((k, i) -> Optional.empty()));
        DevMindException e = assertThrows(DevMindException.class,
                () -> ctl.pull(DecisionLabBundleProvider.KIND_FINETUNE, "f1", "tok"));
        assertEquals(404, e.getErrorCode().getStatus());
        assertTrue(e.getMessage().contains("f1"), e.getMessage());
    }

    @Test
    void notFoundWhenLabModuleAbsent() {
        when(nodeService.resolveByToken("tok")).thenReturn(Optional.of(new AgentNodeEntity()));
        AgentLabBundleController ctl = new AgentLabBundleController(nodeService, providerOf(null));
        assertEquals(404, assertThrows(DevMindException.class,
                () -> ctl.pull(DecisionLabBundleProvider.KIND_EVALUATION, "e1", "tok")).getErrorCode().getStatus());
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<DecisionLabBundleProvider> providerOf(DecisionLabBundleProvider p) {
        ObjectProvider<DecisionLabBundleProvider> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(p);
        return provider;
    }
}
