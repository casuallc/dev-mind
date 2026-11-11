package com.devmind.security.repo;

import com.devmind.security.model.VulnIntelEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface VulnIntelRepository extends JpaRepository<VulnIntelEntity, String>,
        JpaSpecificationExecutor<VulnIntelEntity> {

    List<VulnIntelEntity> findByCveIn(Collection<String> cves);

    /** 情报总览统计（后台漏洞情报页） */
    long countByKevTrue();

    Optional<VulnIntelEntity> findTopByOrderByUpdatedAtDesc();

    /** NVD 元数据回灌：尚无描述的行按 EPSS 百分位降序取前 N（FR-10-5） */
    List<VulnIntelEntity> findByDescriptionIsNullOrderByEpssPercentileDesc(Pageable pageable);
}
