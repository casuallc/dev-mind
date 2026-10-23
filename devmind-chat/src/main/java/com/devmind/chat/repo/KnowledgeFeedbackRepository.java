package com.devmind.chat.repo;

import com.devmind.chat.model.KnowledgeFeedbackEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeFeedbackRepository extends JpaRepository<KnowledgeFeedbackEntity, Long> {

    Optional<KnowledgeFeedbackEntity> findByChatIdAndTurnSeqAndCreatedBy(
            String chatId, Long turnSeq, String createdBy);

    List<KnowledgeFeedbackEntity> findByCreatedAtAfterOrderByCreatedAtDesc(Instant since);

    List<KnowledgeFeedbackEntity> findByChatId(String chatId);

    void deleteByChatId(String chatId);
}
