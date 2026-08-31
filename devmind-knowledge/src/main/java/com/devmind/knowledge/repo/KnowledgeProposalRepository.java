package com.devmind.knowledge.repo;

import com.devmind.knowledge.model.KnowledgeProposalEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeProposalRepository extends JpaRepository<KnowledgeProposalEntity, Long> {

    List<KnowledgeProposalEntity> findByStatusOrderByCreatedAtDesc(String status);

    List<KnowledgeProposalEntity> findByOrderByCreatedAtDesc();
}
