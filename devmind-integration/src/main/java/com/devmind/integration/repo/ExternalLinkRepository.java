package com.devmind.integration.repo;

import com.devmind.integration.model.ExternalLinkEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExternalLinkRepository extends JpaRepository<ExternalLinkEntity, Long> {

    List<ExternalLinkEntity> findByProjectIdAndInternalTypeAndInternalId(
            String projectId, String internalType, String internalId);

    /** 幂等查找：同一内部实体在同一集成上已登记过的某类外部对象 */
    Optional<ExternalLinkEntity> findFirstByIntegrationIdAndInternalTypeAndInternalIdAndExternalTypeOrderByIdDesc(
            Long integrationId, String internalType, String internalId, String externalType);

    /** 反向幂等查找：某外部对象（如 Jira issue key）在同一集成上是否已导入过 */
    Optional<ExternalLinkEntity> findFirstByIntegrationIdAndExternalTypeAndExternalKeyOrderByIdDesc(
            Long integrationId, String externalType, String externalKey);

    /** 批量反查：一批内部实体的外部链接（需求列表来源徽标用，避免 N+1） */
    List<ExternalLinkEntity> findByInternalTypeAndInternalIdIn(String internalType, List<String> internalIds);
}
