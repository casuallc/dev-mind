package com.devmind.auth.repo;

import com.devmind.auth.model.MenuItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MenuItemRepository extends JpaRepository<MenuItemEntity, Long> {

    Optional<MenuItemEntity> findByMenuKey(String menuKey);
}
