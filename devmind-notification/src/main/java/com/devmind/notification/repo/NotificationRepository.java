package com.devmind.notification.repo;

import com.devmind.notification.model.NotificationEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<NotificationEntity, Long> {

    List<NotificationEntity> findByReadAtIsNullOrderByCreatedAtDesc();

    List<NotificationEntity> findByOrderByCreatedAtDesc();

    long countByReadAtIsNull();

    List<NotificationEntity> findByLevelAndReadAtIsNullOrderByCreatedAtDesc(String level);

    List<NotificationEntity> findByLevelOrderByCreatedAtDesc(String level);

    /** 去重：同事件类型 + 同实体，指定时间窗内是否已存在（FR-05）。 */
    long countByEventTypeAndEntityIdAndCreatedAtAfter(String eventType, String entityId, Instant after);
}
