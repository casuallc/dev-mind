package com.devmind.auth.repo;

import com.devmind.auth.model.RoleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RoleRepository extends JpaRepository<RoleEntity, String> {

    /** 内置角色在前，其余按编码排序（管理页与授权矩阵展示顺序） */
    List<RoleEntity> findAllByOrderByBuiltinDescCodeAsc();
}
