package com.devmind.execution.runner;

import com.devmind.common.agent.AgentNodeConnector;
import com.devmind.common.agent.AgentProtocol;
import com.devmind.common.exception.DevMindException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CAP-57 proc/pkg 协议门控：在线 + 协议 v15 双关——触发阶段就拒，不让管控台操作
 * 空等 runner 超时（老 runner 静默忽略 proc/pkg 帧）。镜像 requireBundleCapable 语义。
 */
class AgentNodeRouterProcPkgGateTest {

    private AgentNodeConnector connector;
    private AgentNodeRouter router;

    @BeforeEach
    void setUp() {
        connector = mock(AgentNodeConnector.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentNodeConnector> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(connector);
        router = new AgentNodeRouter(provider);
    }

    @Test
    void procGateAcceptsV15() {
        when(connector.isOnline("1")).thenReturn(true);
        when(connector.supports("1", AgentProtocol.PROC_FRAMES)).thenReturn(true);
        assertDoesNotThrow(() -> router.requireProcCapable("1"));
    }

    @Test
    void procGateRejectsV14() {
        when(connector.isOnline("1")).thenReturn(true);
        when(connector.supports("1", AgentProtocol.PROC_FRAMES)).thenReturn(false);
        var e = assertThrows(DevMindException.class, () -> router.requireProcCapable("1"));
        assertTrue(e.getMessage().contains("v15"), e.getMessage());
        assertTrue(e.getMessage().contains("进程管控"), e.getMessage());
    }

    @Test
    void pkgGateAcceptsV15() {
        when(connector.isOnline("1")).thenReturn(true);
        when(connector.supports("1", AgentProtocol.PKG_FRAMES)).thenReturn(true);
        assertDoesNotThrow(() -> router.requirePkgCapable("1"));
    }

    @Test
    void pkgGateRejectsV14() {
        when(connector.isOnline("1")).thenReturn(true);
        when(connector.supports("1", AgentProtocol.PKG_FRAMES)).thenReturn(false);
        var e = assertThrows(DevMindException.class, () -> router.requirePkgCapable("1"));
        assertTrue(e.getMessage().contains("v15"), e.getMessage());
        assertTrue(e.getMessage().contains("安装包分发"), e.getMessage());
    }

    @Test
    void offlineNodeRejected() {
        when(connector.isOnline("1")).thenReturn(false);
        var e = assertThrows(DevMindException.class, () -> router.requireProcCapable("1"));
        assertTrue(e.getMessage().contains("不在线"), e.getMessage());
    }

    @Test
    void connectorAbsentRejected() {
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentNodeConnector> empty = mock(ObjectProvider.class);
        when(empty.getIfAvailable()).thenReturn(null);
        var e = assertThrows(DevMindException.class,
                () -> new AgentNodeRouter(empty).requirePkgCapable("1"));
        assertTrue(e.getMessage().contains("agent 模块未装配"), e.getMessage());
    }
}
