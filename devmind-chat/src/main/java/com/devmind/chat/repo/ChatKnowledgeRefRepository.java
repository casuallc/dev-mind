package com.devmind.chat.repo;

import com.devmind.chat.model.ChatKnowledgeRefEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatKnowledgeRefRepository extends JpaRepository<ChatKnowledgeRefEntity, Long> {

    List<ChatKnowledgeRefEntity> findByChatIdOrderByTurnSeqAscCitationNoAsc(String chatId);

    List<ChatKnowledgeRefEntity> findByChatIdAndTurnSeqOrderByCitationNoAsc(String chatId, long turnSeq);

    void deleteByChatId(String chatId);
}
