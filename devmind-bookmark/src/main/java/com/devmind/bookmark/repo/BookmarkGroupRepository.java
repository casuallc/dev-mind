package com.devmind.bookmark.repo;

import com.devmind.bookmark.model.BookmarkGroupEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BookmarkGroupRepository extends JpaRepository<BookmarkGroupEntity, Long> {

    List<BookmarkGroupEntity> findByOwnerIdOrderBySortOrderAscIdAsc(String ownerId);

    Optional<BookmarkGroupEntity> findByIdAndOwnerId(Long id, String ownerId);

    List<BookmarkGroupEntity> findByParentId(Long parentId);
}
