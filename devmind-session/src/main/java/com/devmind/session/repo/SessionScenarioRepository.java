package com.devmind.session.repo;

import com.devmind.session.model.SessionScenarioEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SessionScenarioRepository extends JpaRepository<SessionScenarioEntity, Long> {

    Optional<SessionScenarioEntity> findByCode(String code);

    boolean existsByCode(String code);

    List<SessionScenarioEntity> findAllByOrderBySortOrderAscIdAsc();
}
