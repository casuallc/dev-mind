package com.devmind.auth.repo;

import com.devmind.auth.model.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<UserEntity, String> {

    Optional<UserEntity> findByUsername(String username);

    interface KnowledgeIdentityRef {
        String getUsername();
        String getRole();
        String getStatus();
    }
    @org.springframework.data.jpa.repository.Query("select u.username as username, u.role as role, u.status as status from UserEntity u where u.username = :username")
    Optional<KnowledgeIdentityRef> findKnowledgeIdentity(@org.springframework.data.repository.query.Param("username") String username);
}
