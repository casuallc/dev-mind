package com.devmind.agent.repo;

import com.devmind.agent.model.AgentConnLogEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface AgentConnLogRepository extends JpaRepository<AgentConnLogEntity, Long> {

    List<AgentConnLogEntity> findByOrderByCreatedAtDescIdDesc(Pageable pageable);

    long deleteByCreatedAtBefore(Instant before);
}
