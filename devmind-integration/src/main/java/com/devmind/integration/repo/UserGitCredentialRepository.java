package com.devmind.integration.repo;

import com.devmind.integration.model.UserGitCredentialEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** CAP-24 user_git_credentials 存取。 */
public interface UserGitCredentialRepository extends JpaRepository<UserGitCredentialEntity, Long> {

    List<UserGitCredentialEntity> findByUserIdOrderByIdAsc(String userId);

    Optional<UserGitCredentialEntity> findByIdAndUserId(Long id, String userId);
}
