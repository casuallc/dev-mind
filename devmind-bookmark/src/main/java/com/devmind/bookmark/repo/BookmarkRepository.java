package com.devmind.bookmark.repo;

import com.devmind.bookmark.model.BookmarkEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BookmarkRepository extends JpaRepository<BookmarkEntity, Long> {

    /** 我的收藏（全量，筛选在服务层做——收藏量级为个人数据，不做服务端分页） */
    List<BookmarkEntity> findByOwnerIdOrderBySortOrderAscCreatedAtDesc(String ownerId);

    /** owner 收口：按 id + owner 查，他人条目查不到 */
    Optional<BookmarkEntity> findByIdAndOwnerId(Long id, String ownerId);

    List<BookmarkEntity> findByGroupId(Long groupId);

    /** 分组删除（默认档）时把组内收藏落未分组 */
    List<BookmarkEntity> findByGroupIdAndOwnerId(Long groupId, String ownerId);

    long countByGroupId(Long groupId);

    /** FR-01 同 URL 重复收藏提示（不拦截） */
    List<BookmarkEntity> findByOwnerIdAndUrl(String ownerId, String url);
}
