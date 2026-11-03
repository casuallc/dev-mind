package com.devmind.project.authz;

import com.devmind.common.authz.AuthorizationDecision;
import com.devmind.common.authz.ReasonCodes;
import com.devmind.common.authz.ResolvedSubject;
import com.devmind.project.model.ProjectEntity;
import com.devmind.project.repo.ProjectMemberRepository;
import com.devmind.project.repo.ProjectRepository;
import org.springframework.stereotype.Component;

/**
 * CAP-62 / CAP-108 M1 项目读写判定事实源（PROJECT_READ/PROJECT_WRITE 策略与查询范围共用）：
 * <ul>
 *   <li>项目不存在 → RESOURCE_HIDDEN（对外 404，不暴露存在性）</li>
 *   <li>SYSTEM / ADMIN → 放行（平台运维语义；WORKLOG 的 OWNER_ONLY 语义不在此）</li>
 *   <li>WORKLOG → 仅归属用户可读/写，他人一律 RESOURCE_HIDDEN</li>
 *   <li>PUBLIC → 全员可读；写仅成员（非成员 SCOPE_DENIED 403，引导找 OWNER 加成员）</li>
 *   <li>PRIVATE → 仅成员可读/写（非成员 RESOURCE_HIDDEN 404）</li>
 * </ul>
 */
@Component
public class ProjectAccess {

    private final ProjectRepository projectRepo;
    private final ProjectMemberRepository memberRepo;

    public ProjectAccess(ProjectRepository projectRepo, ProjectMemberRepository memberRepo) {
        this.projectRepo = projectRepo;
        this.memberRepo = memberRepo;
    }

    public AuthorizationDecision canRead(ResolvedSubject subject, String projectId) {
        ProjectEntity p = find(projectId);
        if (p == null) {
            return hidden();
        }
        if (subject.system() || subject.admin()) {
            return AuthorizationDecision.allow();
        }
        if (ProjectEntity.KIND_WORKLOG.equals(p.getKind())) {
            return ownedBy(p, subject) ? AuthorizationDecision.allow() : hidden();
        }
        if (ProjectEntity.VISIBILITY_PUBLIC.equals(p.getVisibility())) {
            return AuthorizationDecision.allow();
        }
        return isMember(projectId, subject.username()) ? AuthorizationDecision.allow() : hidden();
    }

    public AuthorizationDecision canWrite(ResolvedSubject subject, String projectId) {
        ProjectEntity p = find(projectId);
        if (p == null) {
            return hidden();
        }
        if (subject.system() || subject.admin()) {
            return AuthorizationDecision.allow();
        }
        if (ProjectEntity.KIND_WORKLOG.equals(p.getKind())) {
            return ownedBy(p, subject) ? AuthorizationDecision.allow() : hidden();
        }
        if (isMember(projectId, subject.username())) {
            return AuthorizationDecision.allow();
        }
        // PUBLIC 可见但不可写 → 403 并给出下一步；PRIVATE 不可见 → 404
        return ProjectEntity.VISIBILITY_PUBLIC.equals(p.getVisibility())
                ? AuthorizationDecision.deny(ReasonCodes.SCOPE_DENIED,
                        "仅项目成员可写，请联系项目 OWNER 添加成员: " + projectId)
                : hidden();
    }

    public boolean isMember(String projectId, String username) {
        return username != null && memberRepo.existsByProjectIdAndUserId(projectId, username);
    }

    private ProjectEntity find(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return null;
        }
        return projectRepo.findById(projectId).orElse(null);
    }

    private static boolean ownedBy(ProjectEntity p, ResolvedSubject subject) {
        return p.getOwnerId() != null && p.getOwnerId().equals(subject.username());
    }

    private static AuthorizationDecision hidden() {
        return AuthorizationDecision.deny(ReasonCodes.RESOURCE_HIDDEN, "项目不存在或不可见");
    }
}
