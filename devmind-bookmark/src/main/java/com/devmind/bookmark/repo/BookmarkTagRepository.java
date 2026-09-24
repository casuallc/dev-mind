package com.devmind.bookmark.repo;

import com.devmind.bookmark.model.BookmarkTagEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BookmarkTagRepository extends JpaRepository<BookmarkTagEntity, Long> {

    List<BookmarkTagEntity> findByOwnerIdOrderByNameAsc(String ownerId);

    Optional<BookmarkTagEntity> findByIdAndOwnerId(Long id, String ownerId);

    Optional<BookmarkTagEntity> findByOwnerIdAndName(String ownerId, String name);
}
