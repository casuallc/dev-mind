package com.devmind.session.repo;

import com.devmind.session.model.TerminalSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** CAP-115 terminal_sessions 仓库。 */
public interface TerminalSessionRepository extends JpaRepository<TerminalSessionEntity, String> {

    List<TerminalSessionEntity> findByWorkspaceIdOrderByStartedAtDesc(String workspaceId);

    List<TerminalSessionEntity> findByState(String state);
}
