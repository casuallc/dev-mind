package com.devmind.artifact;

import com.devmind.artifact.dto.ArtifactView;
import com.devmind.artifact.model.ArtifactEntity;
import com.devmind.artifact.repo.ArtifactRepository;
import com.devmind.auth.IdentityService;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 制品服务（CAP-13 工作产物登记）：build 成功时按日志 artifact= 行登记；
 * 信息类产物（DOC/REVIEW/ANALYSIS 等）由会话/流程层登记；deploy/test/release 消费时按 id 或 producer 反查。
 */
@Service
public class ArtifactService {

    public static final String PRODUCER_BUILD = "BUILD";
    public static final String PRODUCER_SESSION = "SESSION";
    public static final String PRODUCER_TEST_RUN = "TEST_RUN";
    public static final String PRODUCER_DOC = "DOC";
    public static final String PRODUCER_MANUAL = "MANUAL";

    private final ArtifactRepository repo;
    private final IdentityService identityService;

    public ArtifactService(ArtifactRepository repo, IdentityService identityService) {
        this.repo = repo;
        this.identityService = identityService;
    }

    /** 登记产物（同一生产者重复登记时先清旧，保证一构建一产物集的幂等） */
    public ArtifactView register(String projectId, String workItemId, String requirementId, String path,
                                 String producerType, Long producerId) {
        if (path == null || path.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "产物路径不能为空");
        }
        if (producerType != null && producerId != null) {
            repo.findByProducerTypeAndProducerId(producerType, producerId)
                    .forEach(repo::delete);
        }
        ArtifactEntity a = new ArtifactEntity();
        a.setProjectId(projectId);
        a.setWorkItemId(workItemId);
        a.setRequirementId(requirementId);
        a.setType(ArtifactEntity.TYPE_PACKAGE);
        a.setName(nameOf(path));
        a.setStorage(ArtifactEntity.STORAGE_LOCAL);
        a.setPath(path.trim());
        a.setProducerType(producerType);
        a.setProducerId(producerId);
        a.setCreatedBy(identityService.currentActor());
        a.setCreatedAt(Instant.now());
        return toView(repo.save(a));
    }

    public ArtifactEntity requireArtifact(Long id) {
        return repo.findById(id)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "制品不存在: " + id));
    }

    public ArtifactView get(Long id) {
        return toView(requireArtifact(id));
    }

    public List<ArtifactView> list(String projectId, String workItemId, String requirementId) {
        List<ArtifactEntity> list;
        if (workItemId != null && !workItemId.isBlank()) {
            list = repo.findByProjectIdAndWorkItemIdOrderByIdDesc(projectId, workItemId);
        } else if (requirementId != null && !requirementId.isBlank()) {
            list = repo.findByProjectIdAndRequirementIdOrderByIdDesc(projectId, requirementId);
        } else {
            list = repo.findByProjectIdOrderByIdDesc(projectId);
        }
        return list.stream().map(this::toView).toList();
    }

    /** 某次构建/发版登记的产物 */
    public List<ArtifactView> byProducer(String producerType, Long producerId) {
        return repo.findByProducerTypeAndProducerId(producerType, producerId).stream()
                .map(this::toView).toList();
    }

    private String nameOf(String path) {
        String p = path.trim().replace('\\', '/');
        int idx = p.lastIndexOf('/');
        return idx >= 0 ? p.substring(idx + 1) : p;
    }

    public ArtifactView toView(ArtifactEntity a) {
        return new ArtifactView(a.getId(), a.getProjectId(), a.getWorkItemId(), a.getRequirementId(),
                a.getType(), a.getName(),
                a.getVersion(), a.getChecksum(), a.getStorage(), a.getPath(), a.getProducerType(),
                a.getProducerId(), a.getCreatedBy(), a.getCreatedAt());
    }
}
