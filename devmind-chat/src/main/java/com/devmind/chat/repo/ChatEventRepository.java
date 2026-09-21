package com.devmind.chat.repo;

import com.devmind.chat.model.ChatEventEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatEventRepository extends JpaRepository<ChatEventEntity, Long> {

    List<ChatEventEntity> findByChatIdAndSeqGreaterThanOrderBySeqAsc(String chatId, long afterSeq);

    /**
     * CAP-49：模型执行体重建多轮上下文——取该轮之前<b>最近</b>的一段（倒序取、调用方反转）。
     * 用倒序 + 页大小而非"从头取到 beforeSeq"：长会话几千条事件，从头取会把内存与延迟都拖垮。
     */
    List<ChatEventEntity> findByChatIdAndSeqLessThanOrderBySeqDesc(String chatId, long beforeSeq, Pageable limit);

    void deleteByChatId(String chatId);
}
