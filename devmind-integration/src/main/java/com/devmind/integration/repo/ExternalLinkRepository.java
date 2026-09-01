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
}
