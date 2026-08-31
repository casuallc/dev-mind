package com.devmind.session.repo;

import com.devmind.session.model.SessionEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SessionEventRepository extends JpaRepository<SessionEventEntity, Long> {

    List<SessionEventEntity> findBySessionIdAndSeqGreaterThanOrderBySeqAsc(String sessionId, long afterSeq);

    long countBySessionId(String sessionId);

    void deleteBySessionId(String sessionId);
}
