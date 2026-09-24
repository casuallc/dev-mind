package com.devmind.bookmark.repo;

import com.devmind.bookmark.model.BookmarkAccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BookmarkAccountRepository extends JpaRepository<BookmarkAccountEntity, Long> {

    List<BookmarkAccountEntity> findByBookmarkIdOrderBySortOrderAscIdAsc(Long bookmarkId);

    List<BookmarkAccountEntity> findByBookmarkIdIn(Collection<Long> bookmarkIds);

    Optional<BookmarkAccountEntity> findByIdAndBookmarkId(Long id, Long bookmarkId);

    void deleteByBookmarkId(Long bookmarkId);
}
