package com.devmind.security.repo;

import com.devmind.security.model.SecurityFindingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SecurityFindingRepository extends JpaRepository<SecurityFindingEntity, Long> {

    List<SecurityFindingEntity> findByScanId(Long scanId);

    List<SecurityFindingEntity> findByScanIdIn(Collection<Long> scanIds);

    List<SecurityFindingEntity> findByScanIdAndEngine(Long scanId, String engine);

    Optional<SecurityFindingEntity> findByScanIdAndFingerprint(Long scanId, String fingerprint);

    List<SecurityFindingEntity> findByScanIdAndStatus(Long scanId, String status);

    /** FR-08/11-4：session.completed 事件按会话反查 finding 回写 */
    List<SecurityFindingEntity> findByReviewSessionId(String reviewSessionId);

    List<SecurityFindingEntity> findByFixSessionId(String fixSessionId);

    /** 详情页历史轨迹：同 fingerprint 跨扫描的全部行 */
    List<SecurityFindingEntity> findByFingerprintOrderByIdDesc(String fingerprint);
}
