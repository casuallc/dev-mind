package com.devmind.worklog.service;

import com.devmind.auth.IdentityService;
import com.devmind.common.agent.AgentNodeConnector;
import com.devmind.common.agent.WorklogPushResult;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.integration.GitIdentityProvider;
import com.devmind.common.integration.RepoGitGateway;
import com.devmind.project.ProjectService;
import com.devmind.project.model.ProjectEntity;
import com.devmind.worklog.model.WorklogUserSettingsEntity;
import com.devmind.worklog.repo.WorklogUserSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CAP-41 M3 {@link WorklogRemoteBackupService}：前置校验链（未绑定 400 / 无 PAT 409 /
 * 空间未初始化 409）与 file:// 免凭证直推。
 */
class WorklogRemoteBackupServiceTest {

    private WorklogUserSettingsRepository settingsRepo;
    private IdentityService identity;
    private ProjectService projectService;
    private AgentNodeConnector connector;
    private RepoGitGateway gateway;
    private GitIdentityProvider gitIdentity;
    private WorklogRemoteBackupService service;

    @BeforeEach
    void setUp() {
        settingsRepo = mock(WorklogUserSettingsRepository.class);
        identity = mock(IdentityService.class);
        projectService = mock(ProjectService.class);
        connector = mock(AgentNodeConnector.class);
        gateway = mock(RepoGitGateway.class);
        gitIdentity = mock(GitIdentityProvider.class);
        ObjectProvider<AgentNodeConnector> connectorProvider = mockProvider(connector);
        ObjectProvider<RepoGitGateway> gatewayProvider = mockProvider(gateway);
        service = new WorklogRemoteBackupService(settingsRepo, identity, projectService,
                connectorProvider, gatewayProvider, mockProvider(gitIdentity));
        when(identity.currentActor()).thenReturn("alice");
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> mockProvider(T bean) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(bean);
        return p;
    }

    private WorklogUserSettingsEntity boundSettings(String url, String branch) {
        WorklogUserSettingsEntity e = new WorklogUserSettingsEntity();
        e.setUserId("alice");
        e.setRemoteUrl(url);
        e.setRemoteBranch(branch);
        return e;
    }

    private ProjectEntity worklogProject() {
        ProjectEntity p = new ProjectEntity();
        p.setId("pw1");
        p.setAgentNodeId("1");
        return p;
    }

    @Test
    void unboundRemoteRejected() {
        when(settingsRepo.findByUserId("alice")).thenReturn(Optional.empty());
        DevMindException e = assertThrows(DevMindException.class, () -> service.pushToRemote());
        assertTrue(e.getMessage().contains("未绑定远程仓库"), e.getMessage());
    }

    @Test
    void httpsWithoutPersonalPatRejected() {
        when(settingsRepo.findByUserId("alice"))
                .thenReturn(Optional.of(boundSettings("https://git.example.com/u/w.git", null)));
        when(gateway.resolveToken(eq("alice"), eq("git.example.com"), any()))
                .thenReturn(Optional.empty());
        DevMindException e = assertThrows(DevMindException.class, () -> service.pushToRemote());
        assertTrue(e.getMessage().contains("个人访问令牌"), e.getMessage());
        assertTrue(e.getMessage().contains("git.example.com"), e.getMessage());
    }

    @Test
    void workspaceNotInitializedRejected() {
        when(settingsRepo.findByUserId("alice"))
                .thenReturn(Optional.of(boundSettings("https://git.example.com/u/w.git", "main")));
        when(gateway.resolveToken(eq("alice"), eq("git.example.com"), any()))
                .thenReturn(Optional.of("tok"));
        when(projectService.findWorklogByOwner("alice")).thenReturn(Optional.empty());
        DevMindException e = assertThrows(DevMindException.class, () -> service.pushToRemote());
        assertTrue(e.getMessage().contains("未初始化"), e.getMessage());
    }

    @Test
    void httpsPushesWithResolvedToken() {
        when(settingsRepo.findByUserId("alice"))
                .thenReturn(Optional.of(boundSettings("https://git.example.com/u/w.git", null)));
        when(gateway.resolveToken(eq("alice"), eq("git.example.com"), any()))
                .thenReturn(Optional.of("tok"));
        when(gitIdentity.resolveAuthor("alice", "git.example.com"))
                .thenReturn(Optional.of(new GitIdentityProvider.GitAuthor("Alice 爱丽", "alice@gl.com")));
        when(projectService.findWorklogByOwner("alice")).thenReturn(Optional.of(worklogProject()));
        when(connector.pushWorklog(eq("1"), eq("alice"), eq("https://git.example.com/u/w.git"),
                eq("main"), eq("tok"), any()))
                .thenReturn(WorklogPushResult.ok("分支 main：Everything up-to-date"));

        WorklogPushResult r = service.pushToRemote();
        assertTrue(r.ok());
        // 分支空 → 默认 main；token 来自个人 PAT；署名 = 第三方账号 git user/email
        verify(connector).pushWorklog("1", "alice", "https://git.example.com/u/w.git", "main", "tok",
                new GitIdentityProvider.GitAuthor("Alice 爱丽", "alice@gl.com"));
    }

    @Test
    void authorResolutionFailureDoesNotBlockPush() {
        // 署名解析异常 → operator=null 帧不带身份字段（runner 回退内置署名），不阻塞推送
        when(settingsRepo.findByUserId("alice"))
                .thenReturn(Optional.of(boundSettings("https://git.example.com/u/w.git", "main")));
        when(gateway.resolveToken(eq("alice"), eq("git.example.com"), any()))
                .thenReturn(Optional.of("tok"));
        when(gitIdentity.resolveAuthor(any(), any())).thenThrow(new RuntimeException("boom"));
        when(projectService.findWorklogByOwner("alice")).thenReturn(Optional.of(worklogProject()));
        when(connector.pushWorklog("1", "alice", "https://git.example.com/u/w.git", "main", "tok", null))
                .thenReturn(WorklogPushResult.ok("ok"));

        WorklogPushResult r = service.pushToRemote();
        assertTrue(r.ok());
        verify(connector).pushWorklog("1", "alice", "https://git.example.com/u/w.git", "main", "tok", null);
    }

    @Test
    void fileSchemeSkipsTokenResolution() {
        when(settingsRepo.findByUserId("alice"))
                .thenReturn(Optional.of(boundSettings("file:///tmp/worklog-origin.git", "main")));
        when(projectService.findWorklogByOwner("alice")).thenReturn(Optional.of(worklogProject()));
        when(connector.pushWorklog("1", "alice", "file:///tmp/worklog-origin.git", "main", null, null))
                .thenReturn(WorklogPushResult.ok("ok"));

        WorklogPushResult r = service.pushToRemote();
        assertTrue(r.ok());
        verify(connector).pushWorklog("1", "alice", "file:///tmp/worklog-origin.git", "main", null, null);
        // file:// 无凭证语义，不得触碰 gateway
        org.mockito.Mockito.verifyNoInteractions(gateway);
    }
}
