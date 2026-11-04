package com.devmind.security.repo;

import com.devmind.security.model.SecurityUploadTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SecurityUploadTokenRepository extends JpaRepository<SecurityUploadTokenEntity, Long> {

    Optional<SecurityUploadTokenEntity> findByTokenHash(String tokenHash);

    void deleteByScanId(Long scanId);
}
