package com.devmind.bookmark.repo;

import com.devmind.bookmark.model.BookmarkShareEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BookmarkShareRepository extends JpaRepository<BookmarkShareEntity, Long> {

    /** 我发出的分享 */
    List<BookmarkShareEntity> findByOwnerIdOrderByCreatedAtDesc(String ownerId);

    /** 我收到的分享（FR-07 接收方命名空间） */
    List<BookmarkShareEntity> findByTargetUserOrderByCreatedAtDesc(String targetUser);

    Optional<BookmarkShareEntity> findByIdAndOwnerId(Long id, String ownerId);

    /** 服务层判重：NULL 列需分开查（SQL 唯一约束不覆盖含 NULL 的组合） */
    Optional<BookmarkShareEntity> findByOwnerIdAndTargetUserAndBookmarkId(String ownerId, String targetUser, Long bookmarkId);

    Optional<BookmarkShareEntity> findByOwnerIdAndTargetUserAndGroupId(String ownerId, String targetUser, Long groupId);

    List<BookmarkShareEntity> findByBookmarkId(Long bookmarkId);

    List<BookmarkShareEntity> findByGroupId(Long groupId);

    void deleteByBookmarkId(Long bookmarkId);

    void deleteByGroupId(Long groupId);
}
