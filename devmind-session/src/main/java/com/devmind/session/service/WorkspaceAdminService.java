package com.devmind.session.service;

import com.devmind.project.RequirementService;
import com.devmind.session.dto.WorkspaceRow;
import com.devmind.session.model.SessionEntity;
import com.devmind.session.model.SessionRepoEntity;
import com.devmind.session.repo.SessionRepoRepository;
import com.devmind.session.repo.SessionRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作区管理 tab 数据源：把 sessions 表里有固定工作区的会话按「节点上的工作区桶」聚合——
 * keyed（CAP-51）= {@code <workspaceKey>@<owner>}；旧布局（CAP-42）= {@code work@<owner>}。
 * 一行对应 runner 上一块 worktree，供页面收口/清理/查收口记录。
 */
@Service
public class WorkspaceAdminService {

    private final SessionRepository sessionRepo;
    private final SessionRepoRepository sessionRepoRepo;
    private final RequirementService requirementService;

    public WorkspaceAdminService(SessionRepository sessionRepo,
                                 SessionRepoRepository sessionRepoRepo,
                                 RequirementService requirementService) {
        this.sessionRepo = sessionRepo;
        this.sessionRepoRepo = sessionRepoRepo;
        this.requirementService = requirementService;
    }

    public List<WorkspaceRow> list(String projectId) {
        Map<String, List<SessionEntity>> groups = new LinkedHashMap<>();
        for (SessionEntity s : sessionRepo.findByProjectIdOrderByCreatedAtDesc(projectId)) {
            if (s.getWorkspaceState() == null || s.getWorkspaceState().isBlank()) {
                continue; // 旧布局/非代码会话：无固定工作区可管
            }
            if (s.getAgentNodeId() == null || s.getAgentNodeId().isBlank()) {
                continue; // 历史本机会话：无节点可操作
            }
            String owner = ownerOf(s);
            String key = s.getWorkspaceKey() != null && !s.getWorkspaceKey().isBlank()
                    ? s.getWorkspaceKey() + "@" + owner
                    : "work@" + owner;
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
        }
        List<WorkspaceRow> out = new ArrayList<>();
        for (Map.Entry<String, List<SessionEntity>> e : groups.entrySet()) {
            List<SessionEntity> members = e.getValue(); // findBy…CreatedAtDesc → 首元素最新
            SessionEntity latest = members.get(0);
            String state = members.stream()
                    .anyMatch(m -> SessionEntity.WORKSPACE_OPEN.equals(m.getWorkspaceState()))
                    ? SessionEntity.WORKSPACE_OPEN
                    : members.stream()
                    .anyMatch(m -> SessionEntity.WORKSPACE_FINALIZED.equals(m.getWorkspaceState()))
                    ? SessionEntity.WORKSPACE_FINALIZED : SessionEntity.WORKSPACE_RELEASED;
            SessionEntity detailRef = members.stream()
                    .filter(m -> m.getFinalizeDetail() != null && !m.getFinalizeDetail().isBlank())
                    .findFirst().orElse(null);
            SessionEntity actionRef = members.stream()
                    .filter(m -> SessionEntity.WORKSPACE_OPEN.equals(m.getWorkspaceState()))
                    .findFirst().orElse(latest);
            List<SessionRepoEntity> repos =
                    sessionRepoRepo.findBySessionIdOrderBySortOrderAscIdAsc(latest.getId());
            out.add(new WorkspaceRow(
                    e.getKey(), ownerOf(latest), latest.getRequirementId(),
                    requirementTitleOf(latest),
                    repos.isEmpty() ? null : repos.get(0).getBranch(),
                    repos.stream().map(SessionRepoEntity::getName).toList(),
                    latest.getAgentNodeId(), state,
                    actionRef.getId(), detailRef != null ? detailRef.getId() : null,
                    members.size(), detailRef != null ? detailRef.getFinalizedAt() : null,
                    latest.getUpdatedAt()));
        }
        return out;
    }

    private static String ownerOf(SessionEntity s) {
        return s.getWorkspaceOwner() != null && !s.getWorkspaceOwner().isBlank()
                ? s.getWorkspaceOwner() : String.valueOf(s.getCreatedBy());
    }

    private String requirementTitleOf(SessionEntity s) {
        if (s.getRequirementId() == null || s.getRequirementId().isBlank()) {
            return null;
        }
        try {
            return requirementService.requireById(s.getRequirementId()).getTitle();
        } catch (Exception e) {
            return null; // 需求已删：行仍展示，标题留空
        }
    }
}
