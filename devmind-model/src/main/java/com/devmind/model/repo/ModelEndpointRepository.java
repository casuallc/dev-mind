package com.devmind.model.repo;

import com.devmind.model.ModelEndpointEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ModelEndpointRepository extends JpaRepository<ModelEndpointEntity, Long> {

    List<ModelEndpointEntity> findAllByOrderByIdAsc();

    List<ModelEndpointEntity> findByKindOrderByIdAsc(String kind);

    long countByKind(String kind);

    /** 平台默认端点（active）——解析链的第二级 */
    Optional<ModelEndpointEntity> findFirstByKindAndIsDefaultTrueAndStatus(String kind, String status);

    /** 解析链落空时的诊断用：任取一个 active 端点 */
    Optional<ModelEndpointEntity> findFirstByKindAndStatusOrderByIdAsc(String kind, String status);

    /** 设为默认时在单事务内取消同类型旧默认（表上无部分唯一索引，唯一性由 setDefault 的服务层保证） */
    @Modifying
    @Query("update ModelEndpointEntity e set e.isDefault = false, e.updatedAt = CURRENT_TIMESTAMP "
            + "where e.kind = :kind and e.id <> :id and e.isDefault = true")
    int clearDefaultExcept(@Param("kind") String kind, @Param("id") long id);
}
