package com.devmind.knowledge.repo;
import com.devmind.knowledge.model.KnowledgeDerivedArtifactEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface KnowledgeDerivedArtifactRepository extends JpaRepository<KnowledgeDerivedArtifactEntity,Long> {
    Optional<KnowledgeDerivedArtifactEntity> findByResourceTypeAndResourceId(String type,Long id);
}
