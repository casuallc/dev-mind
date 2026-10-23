package com.devmind.chat.repo;

import com.devmind.chat.model.KnowledgeFeedbackRefEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeFeedbackRefRepository extends JpaRepository<KnowledgeFeedbackRefEntity, Long> {

    List<KnowledgeFeedbackRefEntity> findByFeedbackIdIn(Collection<Long> feedbackIds);

    void deleteByFeedbackId(Long feedbackId);

    void deleteByFeedbackIdIn(Collection<Long> feedbackIds);
}
