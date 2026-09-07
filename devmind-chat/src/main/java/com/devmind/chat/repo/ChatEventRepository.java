package com.devmind.chat.repo;

import com.devmind.chat.model.ChatEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatEventRepository extends JpaRepository<ChatEventEntity, Long> {

    List<ChatEventEntity> findByChatIdAndSeqGreaterThanOrderBySeqAsc(String chatId, long afterSeq);

    void deleteByChatId(String chatId);
}
