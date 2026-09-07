package com.devmind.chat.repo;

import com.devmind.chat.model.ChatSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatSessionRepository extends JpaRepository<ChatSessionEntity, String> {

    /** 个人问答列表：按创建人 + 时间倒序 */
    List<ChatSessionEntity> findByCreatedByOrderByCreatedAtDesc(String createdBy);
}
