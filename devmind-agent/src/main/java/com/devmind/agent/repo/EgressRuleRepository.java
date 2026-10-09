package com.devmind.agent.repo;

import com.devmind.agent.model.EgressRuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EgressRuleRepository extends JpaRepository<EgressRuleEntity, Long> {

    List<EgressRuleEntity> findAllByOrderBySortAscIdAsc();
}
