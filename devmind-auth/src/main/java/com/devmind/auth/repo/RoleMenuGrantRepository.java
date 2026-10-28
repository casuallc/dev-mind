package com.devmind.auth.repo;

import com.devmind.auth.model.RoleMenuGrantEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RoleMenuGrantRepository extends JpaRepository<RoleMenuGrantEntity, Long> {

    List<RoleMenuGrantEntity> findByRole(String role);

    void deleteByRole(String role);
}
