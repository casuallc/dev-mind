package com.devmind.session.repo;

import com.devmind.session.model.SessionTemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SessionTemplateRepository extends JpaRepository<SessionTemplateEntity, Long> {

    List<SessionTemplateEntity> findByEnabledTrueOrderBySortOrderAsc();

    Optional<SessionTemplateEntity> findByCode(String code);
}
