package com.devmind.session.repo;

import com.devmind.session.model.SessionRepoEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SessionRepoRepository extends JpaRepository<SessionRepoEntity, Long> {

    List<SessionRepoEntity> findBySessionIdOrderBySortOrderAscIdAsc(String sessionId);

    /** 列表批量取（修 N+1：toView 逐会话单查，几百个会话就是几百次往返） */
    List<SessionRepoEntity> findBySessionIdInOrderBySortOrderAscIdAsc(java.util.Collection<String> sessionIds);

    void deleteBySessionId(String sessionId);
}
