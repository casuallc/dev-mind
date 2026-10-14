package com.devmind.integration.repo;

import com.devmind.integration.model.UserPlatformAccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** CAP-35 user_platform_accounts 存取。 */
public interface UserPlatformAccountRepository extends JpaRepository<UserPlatformAccountEntity, Long> {

    List<UserPlatformAccountEntity> findByUserIdOrderByCreatedAtDesc(String userId);

    Optional<UserPlatformAccountEntity> findByUserIdAndIntegrationId(String userId, Long integrationId);

    /** CAP-75 FR-11：按集成 + 第三方用户名（FEISHU=open_id）反查绑定，机器人身份解析用 */
    Optional<UserPlatformAccountEntity> findByIntegrationIdAndUsername(Long integrationId, String username);
}
