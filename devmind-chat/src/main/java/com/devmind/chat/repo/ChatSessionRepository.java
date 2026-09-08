package com.devmind.chat.repo;

import com.devmind.chat.model.ChatSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatSessionRepository extends JpaRepository<ChatSessionEntity, String> {

    /** 个人问答列表：按创建人 + 时间倒序 */
    List<ChatSessionEntity> findByCreatedByOrderByCreatedAtDesc(String createdBy);

    /** CAP-34 FR-04：hello 对账 DB 兜底——按节点 + 活动状态查存量远程问答（服务端重启后内存 runtime 已丢失） */
    List<ChatSessionEntity> findByAgentNodeIdAndStatusIn(String agentNodeId, java.util.Collection<String> statuses);
}
