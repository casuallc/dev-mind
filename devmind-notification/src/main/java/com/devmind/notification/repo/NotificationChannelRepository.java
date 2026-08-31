package com.devmind.notification.repo;

import com.devmind.notification.model.NotificationChannelEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationChannelRepository extends JpaRepository<NotificationChannelEntity, Long> {

    Optional<NotificationChannelEntity> findByCode(String code);
}
