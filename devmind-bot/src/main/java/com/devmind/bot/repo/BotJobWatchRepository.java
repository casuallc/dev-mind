package com.devmind.bot.repo;

import com.devmind.bot.model.BotJobWatchEntity;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** CAP-75 FR-25 作业终态推送订阅存取。 */
public interface BotJobWatchRepository extends JpaRepository<BotJobWatchEntity, Long> {

    Optional<BotJobWatchEntity> findByEntityTypeAndEntityId(String entityType, String entityId);

    long deleteByCreatedAtBefore(Instant cutoff);
}
