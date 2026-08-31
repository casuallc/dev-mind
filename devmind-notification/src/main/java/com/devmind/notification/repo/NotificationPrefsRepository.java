package com.devmind.notification.repo;

import com.devmind.notification.model.NotificationPrefsEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationPrefsRepository extends JpaRepository<NotificationPrefsEntity, String> {
}
