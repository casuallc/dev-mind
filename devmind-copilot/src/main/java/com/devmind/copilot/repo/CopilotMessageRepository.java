package com.devmind.copilot.repo;

import com.devmind.copilot.model.CopilotMessageEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CopilotMessageRepository extends JpaRepository<CopilotMessageEntity, Long> {

    List<CopilotMessageEntity> findTop100ByConversationIdOrderBySeqDesc(String conversationId);

    long countByConversationId(String conversationId);

    void deleteByConversationId(String conversationId);

    long deleteByCreatedAtBefore(Instant cutoff);
}
