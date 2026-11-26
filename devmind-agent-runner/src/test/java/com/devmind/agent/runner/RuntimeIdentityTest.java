package com.devmind.agent.runner;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 运行身份探测：服务模式/服务名的判定矩阵（whoami 为真实探测，只断言非空兜底）。 */
class RuntimeIdentityTest {

    @Test
    void windowsWithServiceEnvIsWindowsService() {
        RuntimeIdentity id = RuntimeIdentity.detect(
                Map.of(RuntimeIdentity.ENV_SERVICE, "1", RuntimeIdentity.ENV_SERVICE_NAME, "devmind-agent-194"),
                "Windows 11", "");
        assertEquals("windows-service", id.serviceMode());
        assertEquals("devmind-agent-194", id.serviceName());
    }

    @Test
    void windowsWithoutServiceEnvIsProcess() {
        RuntimeIdentity id = RuntimeIdentity.detect(Map.of(), "Windows 11", "");
        assertEquals("process", id.serviceMode());
        assertEquals("", id.serviceName());
    }

    @Test
    void linuxWithInvocationIdIsSystemd() {
        RuntimeIdentity id = RuntimeIdentity.detect(Map.of("INVOCATION_ID", "abc123"), "Linux", "devmind-agent");
        assertEquals("systemd", id.serviceMode());
        // env 缺席时回落 agent.properties 的 serviceName
        assertEquals("devmind-agent", id.serviceName());
    }

    @Test
    void linuxWithoutInvocationIdIsProcess() {
        RuntimeIdentity id = RuntimeIdentity.detect(Map.of(), "Linux", "");
        assertEquals("process", id.serviceMode());
    }

    @Test
    void envServiceNameWinsOverConfig() {
        RuntimeIdentity id = RuntimeIdentity.detect(
                Map.of(RuntimeIdentity.ENV_SERVICE_NAME, "from-env"), "Linux", "from-config");
        assertEquals("from-env", id.serviceName());
    }

    @Test
    void workDirIsUserDir() {
        RuntimeIdentity id = RuntimeIdentity.detect(Map.of(), "Linux", "");
        assertEquals(System.getProperty("user.dir"), id.workDir());
    }
}
