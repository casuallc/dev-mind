package com.devmind.agent.controller;

import com.devmind.agent.model.AgentNodeEntity;
import com.devmind.agent.registry.AgentConnectionRegistry;
import com.devmind.agent.service.AgentAuditLogService;
import com.devmind.agent.service.AgentNodeService;
import com.devmind.agent.service.RunnerPackageService;
import com.devmind.auth.IdentityService;
import com.devmind.common.exception.DevMindException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AgentNodeController#installScript}：双认证（节点 token 归属校验 / 登录态）、
 * 模板占位符全替换、ps1 带 BOM、入口地址推导（X-Forwarded-* 优先）。
 */
class AgentInstallScriptControllerTest {

    private final AgentNodeService nodeService = mock(AgentNodeService.class);
    private final AgentNodeController ctl = new AgentNodeController(
            nodeService, mock(AgentConnectionRegistry.class), mock(RunnerPackageService.class),
            mock(AgentAuditLogService.class), mock(IdentityService.class), List.of());

    private static AgentNodeEntity node(long id) {
        AgentNodeEntity n = new AgentNodeEntity();
        n.setId(id);
        return n;
    }

    private static HttpServletRequest request(String scheme, String host) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getScheme()).thenReturn(scheme);
        when(req.getHeader("Host")).thenReturn(host);
        return req;
    }

    private static String body(ResponseEntity<byte[]> resp) {
        return new String(resp.getBody(), StandardCharsets.UTF_8);
    }

    @Test
    void windowsScriptWithTokenEmbeddedAndBom() {
        when(nodeService.resolveByToken("dmag_x")).thenReturn(Optional.of(node(7)));
        ResponseEntity<byte[]> resp = ctl.installScript(7L, "windows", "dmag_x",
                request("http", "dev.example.com:8080"));
        assertEquals(200, resp.getStatusCode().value());
        // UTF-8 BOM
        assertArrayEquals(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
                new byte[]{resp.getBody()[0], resp.getBody()[1], resp.getBody()[2]});
        assertTrue(resp.getHeaders().getFirst("Content-Disposition").contains("install-runner.ps1"));
        String s = body(resp);
        assertTrue(s.contains("[string]$Token = 'dmag_x'"));
        assertTrue(s.contains("serverUrl=ws://dev.example.com:8080/ws/agent"));
        assertFalse(s.contains("%DOWNLOAD_URL%"));
        assertTrue(s.contains("http://dev.example.com:8080/api/agent-nodes/runner-package/download"));
        assertTrue(s.contains("WinSW") && s.contains("DEVMIND_RUNNER_SERVICE"));
        // https 自签支持：-Insecure 开关 + 校验失败自动降级跳过 + 记入 agent.properties
        assertTrue(s.contains("[switch]$Insecure"));
        assertTrue(s.contains("ServerCertificateValidationCallback"));
        assertTrue(s.contains("$lines += 'insecure=true'"));
    }

    @Test
    void linuxScriptHasSystemdServiceAndNoBom() {
        when(nodeService.resolveByToken("dmag_x")).thenReturn(Optional.of(node(7)));
        ResponseEntity<byte[]> resp = ctl.installScript(7L, "linux", "dmag_x",
                request("http", "dev.example.com"));
        assertArrayEquals(new byte[]{'#', '!', '/'},
                new byte[]{resp.getBody()[0], resp.getBody()[1], resp.getBody()[2]});
        String s = body(resp);
        assertTrue(s.contains("TOKEN=\"dmag_x\""));
        assertTrue(s.contains("systemctl") && s.contains("Environment=DEVMIND_RUNNER_SERVICE=1"));
        assertTrue(s.contains("--no-service"));
        // https 自签支持：--insecure 开关 + curl -k 降级重试 + 记入 agent.properties
        assertTrue(s.contains("--insecure"));
        assertTrue(s.contains("curl -fsSLk"));
        assertTrue(s.contains("INSECURE_LINE=\"insecure=true\""));
        assertFalse(s.contains("%TOKEN%"));
        assertFalse(s.contains("%SERVER_URL%"));
        assertFalse(s.contains("%DOWNLOAD_URL%"));
    }

    @Test
    void honorsForwardedHeadersForWss() {
        when(nodeService.resolveByToken("dmag_x")).thenReturn(Optional.of(node(7)));
        HttpServletRequest req = request("http", "internal:8080");
        when(req.getHeader("X-Forwarded-Proto")).thenReturn("https");
        when(req.getHeader("X-Forwarded-Host")).thenReturn("dev.example.com, proxy.internal");
        String s = body(ctl.installScript(7L, "linux", "dmag_x", req));
        assertTrue(s.contains("serverUrl=wss://dev.example.com/ws/agent"));
        assertTrue(s.contains("https://dev.example.com/api/agent-nodes/runner-package/download"));
    }

    @Test
    void rejectsTokenOfAnotherNode() {
        when(nodeService.resolveByToken("dmag_x")).thenReturn(Optional.of(node(8)));
        DevMindException e = assertThrows(DevMindException.class,
                () -> ctl.installScript(7L, "linux", "dmag_x", request("http", "h")));
        assertEquals(401, e.getErrorCode().getStatus());
    }

    @Test
    void rejectsBadTokenAndAnonymous() {
        when(nodeService.resolveByToken(any())).thenReturn(Optional.empty());
        DevMindException e = assertThrows(DevMindException.class,
                () -> ctl.installScript(7L, "linux", "bad", request("http", "h")));
        assertEquals(401, e.getErrorCode().getStatus());
        // 无 token 且无登录态（SecurityContext 为空）→ 401
        e = assertThrows(DevMindException.class,
                () -> ctl.installScript(7L, "linux", null, request("http", "h")));
        assertEquals(401, e.getErrorCode().getStatus());
    }

    @Test
    void rejectsUnknownOs() {
        DevMindException e = assertThrows(DevMindException.class,
                () -> ctl.installScript(7L, "macos", "dmag_x", request("http", "h")));
        assertEquals(400, e.getErrorCode().getStatus());
    }
}
