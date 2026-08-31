package com.devmind.project;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.project.dto.RelationRequest;
import com.devmind.project.dto.RelationView;
import com.devmind.project.model.RelationEntity;
import com.devmind.project.repo.ProjectRepository;
import com.devmind.project.repo.RelationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Relation（CAP-13 研发主线）：通用横向关系边。归属用外键，本服务只管稀疏横联
 * （depends_on / implements / verifies / fixes / produced_by，类型可扩展）。
 * 端点存在性只做轻校验：主线实体（requirement/design/work_item）查库，其余类型（artifact/session/doc）
 * 由各业务模块自身保证。
 */
@Service
public class RelationService {

    private static final Logger log = LoggerFactory.getLogger(RelationService.class);

    /** 预置关系类型（不强制，新类型可扩展） */
    private static final Set<String> PRESET_TYPES = Set.of(
            RelationEntity.TYPE_DEPENDS_ON,
            RelationEntity.TYPE_IMPLEMENTS,
            RelationEntity.TYPE_VERIFIES,
            RelationEntity.TYPE_FIXES,
            RelationEntity.TYPE_PRODUCED_BY);

    private final ProjectRepository projectRepo;
    private final RelationRepository relationRepo;

    public RelationService(ProjectRepository projectRepo, RelationRepository relationRepo) {
        this.projectRepo = projectRepo;
        this.relationRepo = relationRepo;
    }

    /** 项目内全部边，或某端点涉及的边（fromType/fromId 与 toType/toId 同值查询）。 */
    public List<RelationView> list(String projectId, String fromType, String fromId) {
        requireProject(projectId);
        if (fromType == null || fromType.isBlank() || fromId == null || fromId.isBlank()) {
            return relationRepo.findByProjectIdOrderByCreatedAtDesc(projectId).stream().map(this::toView).toList();
        }
        String t = fromType.trim().toLowerCase(Locale.ROOT);
        return relationRepo.findByFromTypeAndFromIdOrToTypeAndToId(t, fromId, t, fromId)
                .stream().map(this::toView).toList();
    }

    public RelationView create(String projectId, RelationRequest req) {
        requireProject(projectId);
        RelationEntity e = new RelationEntity();
        e.setId(MainlineSupport.shortId());
        e.setProjectId(projectId);
        e.setFromType(req.fromType().trim().toLowerCase(Locale.ROOT));
        e.setFromId(req.fromId().trim());
        e.setToType(req.toType().trim().toLowerCase(Locale.ROOT));
        e.setToId(req.toId().trim());
        e.setRelationType(req.relationType().trim().toLowerCase(Locale.ROOT));
        e.setCreatedAt(Instant.now());
        relationRepo.save(e);
        if (!PRESET_TYPES.contains(e.getRelationType())) {
            log.info("关系边已创建（扩展类型）: {} {} --{}--> {} {}",
                    e.getFromType(), e.getFromId(), e.getRelationType(), e.getToType(), e.getToId());
        } else {
            log.info("关系边已创建: {} {} --{}--> {} {}",
                    e.getFromType(), e.getFromId(), e.getRelationType(), e.getToType(), e.getToId());
        }
        return toView(e);
    }

    public void delete(String projectId, String relationId) {
        RelationEntity e = relationRepo.findById(relationId)
                .filter(x -> x.getProjectId().equals(projectId))
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "关系不存在: " + relationId));
        relationRepo.delete(e);
        log.info("关系边已删除: projectId={} id={}", projectId, relationId);
    }

    private void requireProject(String projectId) {
        projectRepo.findById(projectId)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "项目不存在: " + projectId));
    }

    private RelationView toView(RelationEntity e) {
        return new RelationView(e.getId(), e.getProjectId(), e.getFromType(), e.getFromId(),
                e.getToType(), e.getToId(), e.getRelationType(), e.getCreatedAt());
    }
}
