package com.devmind.copilot.repo;

import com.devmind.copilot.model.CopilotTurnEntity;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface CopilotTurnRepository
        extends JpaRepository<CopilotTurnEntity, String>, JpaSpecificationExecutor<CopilotTurnEntity> {

    long countByActorAndCreatedAtAfter(String actor, Instant after);

    long countByCreatedAtAfter(Instant after);

    long countByCreatedAtBefore(Instant cutoff);

    long deleteByCreatedAtBefore(Instant cutoff);
}
