package com.devmind.session.repo;

import com.devmind.session.model.SessionOutputEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SessionOutputRepository extends JpaRepository<SessionOutputEntity, Long> {

    Optional<SessionOutputEntity> findBySessionIdAndFileName(String sessionId, String fileName);

    List<SessionOutputEntity> findBySessionId(String sessionId);
}
