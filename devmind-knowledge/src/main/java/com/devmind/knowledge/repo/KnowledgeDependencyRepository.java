package com.devmind.knowledge.repo;
import com.devmind.knowledge.model.KnowledgeDependencyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface KnowledgeDependencyRepository extends JpaRepository<KnowledgeDependencyEntity,Long> {
    List<KnowledgeDependencyEntity> findByArtifactId(Long id);
}
