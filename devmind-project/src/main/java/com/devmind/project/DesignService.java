package com.devmind.project;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.project.dto.DesignRequest;
import com.devmind.project.dto.DesignView;
import com.devmind.project.model.DesignEntity;
import com.devmind.project.repo.DesignRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Design（CAP-13 研发主线）：解决方案实体，挂 Requirement 下，docId 指向 CAP-03 方案文档。
 * 复杂需求一份 CONFIRMED 方案是拆 Work Item 的依据；version 在 requirement 内递增，可多次迭代。
 */
@Service
public class DesignService {

    private static final Logger log = LoggerFactory.getLogger(DesignService.class);

    private static final Set<String> STATUSES = Set.of(
            DesignEntity.STATUS_DRAFT,
            DesignEntity.STATUS_CONFIRMED,
            DesignEntity.STATUS_DISCARDED);

    private final RequirementService requirementService;
    private final DesignRepository designRepo;

    public DesignService(RequirementService requirementService, DesignRepository designRepo) {
        this.requirementService = requirementService;
        this.designRepo = designRepo;
    }

    public List<DesignView> list(String projectId, String requirementId) {
        requirementService.requireEntity(projectId, requirementId);
        return designRepo.findByRequirementIdOrderByVersionDesc(requirementId).stream().map(this::toView).toList();
    }

    /** 创建方案：DRAFT 起步，version 在 requirement 内自增。 */
    public synchronized DesignView create(String projectId, String requirementId, DesignRequest req) {
        requirementService.requireEntity(projectId, requirementId);
        DesignEntity e = new DesignEntity();
        e.setId(MainlineSupport.shortId());
        e.setProjectId(projectId);
        e.setRequirementId(requirementId);
        e.setDocId(req.docId());
        Integer max = designRepo.findMaxVersionByRequirementId(requirementId);
        e.setVersion(max == null ? 1 : max + 1);
        e.setStatus(DesignEntity.STATUS_DRAFT);
        e.setCreatedBy("local");
        Instant now = Instant.now();
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        designRepo.save(e);
        log.info("方案已创建: projectId={} requirementId={} v{}", projectId, requirementId, e.getVersion());
        return toView(e);
    }

    public DesignView update(String projectId, String requirementId, String designId, DesignRequest req) {
        DesignEntity e = requireUnder(projectId, requirementId, designId);
        if (req.docId() != null) e.setDocId(req.docId());
        e.setUpdatedAt(Instant.now());
        return toView(designRepo.save(e));
    }

    /** 状态流转：DRAFT / CONFIRMED / DISCARDED，只校验状态值合法。 */
    public DesignView updateStatus(String projectId, String requirementId, String designId, String status) {
        DesignEntity e = requireUnder(projectId, requirementId, designId);
        String next = normalizeStatus(status);
        String prev = e.getStatus();
        e.setStatus(next);
        e.setUpdatedAt(Instant.now());
        DesignView view = toView(designRepo.save(e));
        log.info("方案状态流转: {} v{} {} -> {}", designId, e.getVersion(), prev, next);
        return view;
    }

    public void delete(String projectId, String requirementId, String designId) {
        DesignEntity e = requireUnder(projectId, requirementId, designId);
        designRepo.delete(e);
        log.info("方案已删除: projectId={} requirementId={} v{}", projectId, requirementId, e.getVersion());
    }

    /** 按 id 校验方案归属（供 Work Item 挂 designId 时校验）。 */
    public DesignEntity requireEntity(String projectId, String designId) {
        return designRepo.findById(designId)
                .filter(x -> x.getProjectId().equals(projectId))
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "方案不存在: " + designId));
    }

    private DesignEntity requireUnder(String projectId, String requirementId, String designId) {
        DesignEntity e = requireEntity(projectId, designId);
        if (!e.getRequirementId().equals(requirementId)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "方案 " + designId + " 不属于需求 " + requirementId);
        }
        return e;
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "方案状态不能为空");
        }
        String s = status.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(s)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "非法方案状态: " + status + "（可选 " + String.join("/", STATUSES) + "）");
        }
        return s;
    }

    private DesignView toView(DesignEntity e) {
        return new DesignView(e.getId(), e.getProjectId(), e.getRequirementId(), e.getDocId(),
                e.getVersion(), e.getStatus(), e.getCreatedBy(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
