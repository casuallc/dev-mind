package com.devmind.worklog.repo;

import com.devmind.worklog.model.WorklogUserSettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WorklogUserSettingsRepository extends JpaRepository<WorklogUserSettingsEntity, Long> {

    Optional<WorklogUserSettingsEntity> findByUserId(String userId);
}
