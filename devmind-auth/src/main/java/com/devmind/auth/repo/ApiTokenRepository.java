package com.devmind.auth.repo;

import com.devmind.auth.model.ApiTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ApiTokenRepository extends JpaRepository<ApiTokenEntity, Long> {

    Optional<ApiTokenEntity> findByTokenHash(String tokenHash);
}
