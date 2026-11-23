package com.devmind.session.repo;

import com.devmind.session.model.TerminalWorkspaceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** CAP-115 terminal_workspaces 仓库。 */
public interface TerminalWorkspaceRepository extends JpaRepository<TerminalWorkspaceEntity, String> {

    List<TerminalWorkspaceEntity> findByProjectIdOrderByCreatedAtDesc(String projectId);

    /** 同 key 可有多行（挂接键释放后可再挂接），占用判定在 Service 层按 state=OPEN 过滤 */
    List<TerminalWorkspaceEntity> findByWorkspaceKey(String workspaceKey);

    List<TerminalWorkspaceEntity> findByState(String state);

    List<TerminalWorkspaceEntity> findByRequirementIdAndState(String requirementId, String state);
}
