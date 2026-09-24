package com.devmind.bookmark.repo;

import com.devmind.bookmark.model.BookmarkTagRelEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface BookmarkTagRelRepository extends JpaRepository<BookmarkTagRelEntity, Long> {

    List<BookmarkTagRelEntity> findByBookmarkId(Long bookmarkId);

    List<BookmarkTagRelEntity> findByBookmarkIdIn(Collection<Long> bookmarkIds);

    List<BookmarkTagRelEntity> findByTagId(Long tagId);

    void deleteByBookmarkId(Long bookmarkId);

    void deleteByTagId(Long tagId);
}
