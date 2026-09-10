package com.devmind.integration.repo;

import com.devmind.integration.model.UserPlatformAccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** CAP-35 user_platform_accounts 存取。 */
public interface UserPlatformAccountRepository extends JpaRepository<UserPlatformAccountEntity, Long> {

    List<UserPlatformAccountEntity> findByUserIdOrderByCreatedAtDesc(String userId);

    Optional<UserPlatformAccountEntity> findByUserIdAndIntegrationId(String userId, Long integrationId);
}
