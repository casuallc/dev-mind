package com.devmind.agent.controller;

import com.devmind.agent.model.AgentNodeEntity;
import com.devmind.agent.service.AgentNodeService;
import com.devmind.common.agent.ContextPackageProvider;
import com.devmind.common.agent.exec.ContextPackage;
import com.devmind.common.agent.exec.ContextPackages;
import com.devmind.common.exception.DevMindException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** {@link AgentContextController}：token 认证 + 包供给 + 404 语义。 */
class AgentContextControllerTest {

    private final AgentNodeService nodeService = mock(AgentNodeService.class);

    @Test
    void rejectsInvalidToken() {
        when(nodeService.resolveByToken("bad")).thenReturn(Optional.empty());
        AgentContextController ctl = new AgentContextController(nodeService, providerOf(null));
        DevMindException e = assertThrows(DevMindException.class, () -> ctl.pull("s1", "bad"));
        assertEquals(401, e.getErrorCode().getStatus());
    }

    @Test
    void returnsPackageBytesForValidToken() {
        when(nodeService.resolveByToken("tok")).thenReturn(Optional.of(new AgentNodeEntity()));
        ContextPackage pkg = ContextPackage.of("## 经验", "{}");
        AgentContextController ctl = new AgentContextController(nodeService,
                providerOf(sessionId -> Optional.of(pkg)));
        ResponseEntity<byte[]> resp = ctl.pull("s1", "tok");
        assertEquals(200, resp.getStatusCode().value());
        assertEquals(pkg, ContextPackages.fromJson(resp.getBody()));
    }

    @Test
    void notFoundWhenNoPackage() {
        when(nodeService.resolveByToken("tok")).thenReturn(Optional.of(new AgentNodeEntity()));
        AgentContextController ctl = new AgentContextController(nodeService,
                providerOf(sessionId -> Optional.empty()));
        DevMindException e = assertThrows(DevMindException.class, () -> ctl.pull("s1", "tok"));
        assertEquals(404, e.getErrorCode().getStatus());
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ContextPackageProvider> providerOf(ContextPackageProvider p) {
        ObjectProvider<ContextPackageProvider> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(p);
        return provider;
    }
}
