package com.devmind.auth.repo;

import com.devmind.auth.model.RoleMenuGrantEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface RoleMenuGrantRepository extends JpaRepository<RoleMenuGrantEntity, Long> {

    List<RoleMenuGrantEntity> findByRole(String role);

    boolean existsByRoleAndMenuKeyIn(String role, Collection<String> menuKeys);

    void deleteByRole(String role);
}
