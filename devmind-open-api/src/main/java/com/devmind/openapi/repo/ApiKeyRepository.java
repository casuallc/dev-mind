package com.devmind.openapi.repo;

import com.devmind.openapi.model.ApiKeyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ApiKeyRepository extends JpaRepository<ApiKeyEntity, Long> {

    Optional<ApiKeyEntity> findByAccessKey(String accessKey);
}
