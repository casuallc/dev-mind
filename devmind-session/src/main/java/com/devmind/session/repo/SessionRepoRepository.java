package com.devmind.session.repo;

import com.devmind.session.model.SessionRepoEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SessionRepoRepository extends JpaRepository<SessionRepoEntity, Long> {

    List<SessionRepoEntity> findBySessionIdOrderBySortOrderAscIdAsc(String sessionId);

    void deleteBySessionId(String sessionId);
}
