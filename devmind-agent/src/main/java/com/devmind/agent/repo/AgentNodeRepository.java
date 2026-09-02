package com.devmind.agent.repo;

import com.devmind.agent.model.AgentNodeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AgentNodeRepository extends JpaRepository<AgentNodeEntity, Long> {

    Optional<AgentNodeEntity> findByTokenHash(String tokenHash);

    boolean existsByName(String name);
}
