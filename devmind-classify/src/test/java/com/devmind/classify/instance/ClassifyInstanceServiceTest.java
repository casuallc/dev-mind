package com.devmind.classify.instance;

import com.devmind.auth.IdentityService;
import com.devmind.classify.instance.model.ClassifyInstanceEntity;
import com.devmind.classify.instance.repo.ClassifyInstanceRepository;
import com.devmind.classify.pkg.model.ClassifyPackageInstallEntity;
import com.devmind.classify.pkg.repo.ClassifyPackageInstallRepository;
import com.devmind.common.agent.AgentNodeConnector;
import com.devmind.common.agent.AgentProcCommand;
import com.devmind.common.agent.AgentProcResult;
import com.devmind.common.exception.DevMindException;
import com.devmind.execution.runner.AgentNodeRouter;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CAP-57 FR-02 实例管控组帧：<b>钉死 proc 帧内容</b>（argv/workdir/pidFile/logFile/env 展开）
 * + 前置校验（应用包未装 409、env 引用未安装包 409、协议门控在触发阶段）。假 connector，
 * 不起真实进程。
 */
class ClassifyInstanceServiceTest {

    private ClassifyInstanceRepository repo;
    private ClassifyPackageInstallRepository installRepo;
    private AgentNodeRouter nodeRouter;
    private AgentNodeConnector connector;
    private ClassifyInstanceService service;

    @BeforeEach
    void setUp() {
        repo = mock(ClassifyInstanceRepository.class);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        installRepo = mock(ClassifyPackageInstallRepository.class);
        nodeRouter = mock(AgentNodeRouter.class);
        connector = mock(AgentNodeConnector.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentNodeConnector> connectorProvider = mock(ObjectProvider.class);
        when(connectorProvider.getIfAvailable()).thenReturn(connector);
        IdentityService identityService = mock(IdentityService.class);
        when(identityService.currentActor()).thenReturn("tester");
        service = new ClassifyInstanceService(repo, installRepo, nodeRouter, connectorProvider,
                identityService, JsonMapper.builder().build());
    }

    private ClassifyInstanceEntity instance() {
        ClassifyInstanceEntity e = new ClassifyInstanceEntity();
        e.setId(5L);
        e.setName("gpu-8377");
        e.setAgentNodeId("2");
        e.setPort(8377);
        e.setBaseUrl("http://172.20.140.88:8377");
        e.setAppPackageId(9L);
        e.setPythonBin("venv/bin/python");
        e.setEnvJson("{\"LAYA_DEVICE\":\"cpu\",\"LAYA_SLOT_MODELS\":\"{\\\"multilingual\\\":\\\"${PKG_DIR:12}\\\"}\"}");
        e.setStatus(ClassifyInstanceEntity.STATUS_STOPPED);
        return e;
    }

    private ClassifyPackageInstallEntity installed(long packageId, String nodeId, String dir) {
        ClassifyPackageInstallEntity i = new ClassifyPackageInstallEntity();
        i.setPackageId(packageId);
        i.setNodeId(nodeId);
        i.setInstallDir(dir);
        i.setStatus(ClassifyPackageInstallEntity.STATUS_INSTALLED);
        return i;
    }

    private void stubInstalled() {
        when(installRepo.findByPackageIdAndNodeId(9L, "2"))
                .thenReturn(Optional.of(installed(9L, "2", "/opt/runner/classify/packages/pkg-9")));
        when(installRepo.findByPackageIdAndNodeId(12L, "2"))
                .thenReturn(Optional.of(installed(12L, "2", "/opt/runner/classify/packages/pkg-12")));
    }

    @Test
    void startBuildsFullProcFrame() {
        ClassifyInstanceEntity e = instance();
        when(repo.findById(5L)).thenReturn(Optional.of(e));
        stubInstalled();
        when(connector.proc(eq("2"), any()))
                .thenReturn(AgentProcResult.ok("start", "RUNNING", 777L, ""));

        var view = service.start(5L);

        ArgumentCaptor<AgentProcCommand> captor = ArgumentCaptor.forClass(AgentProcCommand.class);
        verify(connector).proc(eq("2"), captor.capture());
        AgentProcCommand cmd = captor.getValue();
        assertEquals("start", cmd.action());
        assertEquals("5", cmd.instanceId());
        assertTrue(cmd.requestId().startsWith("proc-5-"), cmd.requestId());
        assertEquals(List.of("venv/bin/python", "-m", "uvicorn", "app:app",
                "--host", "0.0.0.0", "--port", "8377"), cmd.argv());
        assertEquals("packages/pkg-9", cmd.workdir());
        assertEquals("run/inst-5/proc.pid", cmd.pidFile());
        assertEquals("logs/inst-5.log", cmd.logFile());
        assertEquals("cpu", cmd.env().get("LAYA_DEVICE"));
        // ${PKG_DIR:12} 展开为本节点 installDir（节点侧绝对路径）
        assertEquals("{\"multilingual\":\"/opt/runner/classify/packages/pkg-12\"}",
                cmd.env().get("LAYA_SLOT_MODELS"));
        assertEquals(String.join(" ", cmd.argv()), cmd.command());

        assertEquals(ClassifyInstanceEntity.STATUS_STARTING, view.status());
        verify(nodeRouter).requireProcCapable("2");
    }

    @Test
    void startRejectsWhenAppPackageNotInstalled() {
        ClassifyInstanceEntity e = instance();
        when(repo.findById(5L)).thenReturn(Optional.of(e));
        when(installRepo.findByPackageIdAndNodeId(9L, "2")).thenReturn(Optional.empty());

        var ex = assertThrows(DevMindException.class, () -> service.start(5L));
        assertTrue(ex.getMessage().contains("尚未安装到节点 2"), ex.getMessage());
    }

    @Test
    void startRejectsWhenEnvPackageMissing() {
        ClassifyInstanceEntity e = instance();
        when(repo.findById(5L)).thenReturn(Optional.of(e));
        when(installRepo.findByPackageIdAndNodeId(9L, "2"))
                .thenReturn(Optional.of(installed(9L, "2", "/opt/pkg-9")));
        when(installRepo.findByPackageIdAndNodeId(12L, "2")).thenReturn(Optional.empty());

        var ex = assertThrows(DevMindException.class, () -> service.start(5L));
        assertTrue(ex.getMessage().contains("#12"), ex.getMessage());
    }

    @Test
    void startRejectsWithoutAppPackage() {
        ClassifyInstanceEntity e = instance();
        e.setAppPackageId(null);
        when(repo.findById(5L)).thenReturn(Optional.of(e));

        var ex = assertThrows(DevMindException.class, () -> service.start(5L));
        assertTrue(ex.getMessage().contains("未绑定应用包"), ex.getMessage());
    }

    @Test
    void commandOverrideSplitsArgv() {
        ClassifyInstanceEntity e = instance();
        e.setCommandOverride("venv/bin/python app.py --port 8377");
        when(repo.findById(5L)).thenReturn(Optional.of(e));
        stubInstalled();
        when(connector.proc(eq("2"), any()))
                .thenReturn(AgentProcResult.ok("start", "RUNNING", 1L, ""));

        service.start(5L);

        ArgumentCaptor<AgentProcCommand> captor = ArgumentCaptor.forClass(AgentProcCommand.class);
        verify(connector).proc(eq("2"), captor.capture());
        assertEquals(List.of("venv/bin/python", "app.py", "--port", "8377"), captor.getValue().argv());
    }

    @Test
    void stopSendsStopAndMarksStopped() {
        ClassifyInstanceEntity e = instance();
        e.setStatus(ClassifyInstanceEntity.STATUS_RUNNING);
        when(repo.findById(5L)).thenReturn(Optional.of(e));
        when(connector.proc(eq("2"), any()))
                .thenReturn(AgentProcResult.ok("stop", "STOPPED", null, ""));

        var view = service.stop(5L);

        ArgumentCaptor<AgentProcCommand> captor = ArgumentCaptor.forClass(AgentProcCommand.class);
        verify(connector).proc(eq("2"), captor.capture());
        AgentProcCommand cmd = captor.getValue();
        assertEquals("stop", cmd.action());
        assertEquals("run/inst-5/proc.pid", cmd.pidFile());
        assertEquals(ClassifyInstanceEntity.STATUS_STOPPED, view.status());
    }

    @Test
    void ackFailureKeepsErrorAndThrows() {
        ClassifyInstanceEntity e = instance();
        when(repo.findById(5L)).thenReturn(Optional.of(e));
        stubInstalled();
        when(connector.proc(eq("2"), any()))
                .thenReturn(AgentProcResult.fail("start", "UNKNOWN", "工作目录不存在"));

        var ex = assertThrows(DevMindException.class, () -> service.start(5L));
        assertTrue(ex.getMessage().contains("工作目录不存在"), ex.getMessage());
        assertEquals("工作目录不存在", e.getLastError());
    }

    @Test
    void deleteRequiresStopped() {
        ClassifyInstanceEntity e = instance();
        e.setStatus(ClassifyInstanceEntity.STATUS_RUNNING);
        when(repo.findById(5L)).thenReturn(Optional.of(e));
        var ex = assertThrows(DevMindException.class, () -> service.delete(5L));
        assertTrue(ex.getMessage().contains("先停止"), ex.getMessage());
    }

    @Test
    void liveStatusReconcilesDeadProcess() {
        ClassifyInstanceEntity e = instance();
        e.setStatus(ClassifyInstanceEntity.STATUS_RUNNING);
        when(repo.findById(5L)).thenReturn(Optional.of(e));
        when(connector.proc(eq("2"), any()))
                .thenReturn(AgentProcResult.ok("status", "STOPPED", null, ""));

        var view = service.liveStatus(5L);
        assertEquals(ClassifyInstanceEntity.STATUS_STOPPED, view.status());
        assertTrue(view.lastError().contains("进程已退出"), view.lastError());
    }
}
