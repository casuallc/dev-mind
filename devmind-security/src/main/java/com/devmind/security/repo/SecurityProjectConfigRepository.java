package com.devmind.security.repo;

import com.devmind.security.model.SecurityProjectConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SecurityProjectConfigRepository extends JpaRepository<SecurityProjectConfigEntity, String> {
}
