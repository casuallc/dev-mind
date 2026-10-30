package com.devmind.auth.repo;

import com.devmind.auth.model.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<UserEntity, String> {

    Optional<UserEntity> findByUsername(String username);

    List<UserEntity> findByRoleCode(String roleCode);

    long countByRoleCode(String roleCode);
}
