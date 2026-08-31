package com.devmind.serveradapter.repo;

import com.devmind.serveradapter.model.ScriptTemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ScriptTemplateRepository extends JpaRepository<ScriptTemplateEntity, Long> {

    List<ScriptTemplateEntity> findByProjectIdOrderByCodeAsc(String projectId);

    Optional<ScriptTemplateEntity> findByProjectIdAndCode(String projectId, String code);

    boolean existsByProjectIdAndCode(String projectId, String code);
}
