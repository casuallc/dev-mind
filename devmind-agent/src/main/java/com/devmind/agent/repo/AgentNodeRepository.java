package com.devmind.agent.repo;

import com.devmind.agent.model.AgentNodeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentNodeRepository extends JpaRepository<AgentNodeEntity, Long> {

    Optional<AgentNodeEntity> findByTokenHash(String tokenHash);

    boolean existsByName(String name);

    /** 平台默认节点（全平台至多一个；正常至多返回一条，取 List 便于 setDefault 清除旧标记） */
    List<AgentNodeEntity> findByIsDefaultTrue();
}
