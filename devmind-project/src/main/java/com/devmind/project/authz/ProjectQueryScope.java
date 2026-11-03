package com.devmind.project.authz;

import com.devmind.auth.authz.SubjectResolver;
import com.devmind.common.authz.AuthorizedQueryScope;
import com.devmind.common.authz.ExecutionContextHolder;
import com.devmind.common.authz.ResolvedSubject;
import com.devmind.project.model.ProjectEntity;
import com.devmind.project.repo.ProjectMemberRepository;
import com.devmind.project.repo.ProjectRepository;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * CAP-108 §4.2.3 查询范围实现：列表在内存过滤前计算当前主体可读项目集合。
 * ADMIN/SYSTEM 返回全量；普通主体 = PUBLIC 非 WORKLOG 项目 ∪ 所在成员项目（WORKLOG 归属经成员行覆盖）。
 */
@Component
public class ProjectQueryScope implements AuthorizedQueryScope {

    private final ProjectRepository projectRepo;
    private final ProjectMemberRepository memberRepo;
    private final SubjectResolver subjects;

    public ProjectQueryScope(ProjectRepository projectRepo, ProjectMemberRepository memberRepo,
                             SubjectResolver subjects) {
        this.projectRepo = projectRepo;
        this.memberRepo = memberRepo;
        this.subjects = subjects;
    }

    @Override
    public Set<String> readableProjectIds(ResolvedSubject subject) {
        if (subject.admin() || subject.system()) {
            return projectRepo.findAll().stream().map(ProjectEntity::getId).collect(Collectors.toSet());
        }
        Set<String> readable = projectRepo.findAll().stream()
                .filter(p -> !ProjectEntity.KIND_WORKLOG.equals(p.getKind()))
                .filter(p -> ProjectEntity.VISIBILITY_PUBLIC.equals(p.getVisibility()))
                .map(ProjectEntity::getId)
                .collect(Collectors.toCollection(HashSet::new));
        readable.addAll(memberRepo.findProjectIdsByUserId(subject.username()));
        return readable;
    }

    /**
     * 当前线程上下文口径：无上下文（内部异步调用）→ null（调用方不过滤，M1 软边界）；
     * 上下文存在但主体不可解析 → 空集（宁可看不见，不回退全量）。
     */
    public Set<String> readableProjectIdsForCurrent() {
        var ctx = ExecutionContextHolder.current().orElse(null);
        if (ctx == null) {
            return null;
        }
        var resolution = subjects.resolve(ctx);
        return resolution.resolved() ? readableProjectIds(resolution.subject()) : Set.of();
    }
}
