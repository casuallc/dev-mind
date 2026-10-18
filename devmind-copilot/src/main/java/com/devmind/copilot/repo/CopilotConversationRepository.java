package com.devmind.copilot.repo;

import com.devmind.copilot.model.CopilotConversationEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CopilotConversationRepository extends JpaRepository<CopilotConversationEntity, String> {

    Optional<CopilotConversationEntity> findByChannelAndExternalChatIdAndSenderId(
            String channel, String externalChatId, String senderId);

    List<CopilotConversationEntity> findByLastTurnAtBefore(Instant cutoff);
}
