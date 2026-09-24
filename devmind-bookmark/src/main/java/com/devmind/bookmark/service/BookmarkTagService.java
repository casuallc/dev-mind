package com.devmind.bookmark.service;

import com.devmind.bookmark.dto.BookmarkTagSummary;
import com.devmind.bookmark.model.BookmarkTagEntity;
import com.devmind.bookmark.model.BookmarkTagRelEntity;
import com.devmind.bookmark.repo.BookmarkTagRelRepository;
import com.devmind.bookmark.repo.BookmarkTagRepository;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CAP-64 FR-03 标签：(owner_id, name) 唯一，改名/删除只作用于自己的标签；删除仅解除关联不动收藏。
 */
@Service
public class BookmarkTagService {

    private final BookmarkTagRepository tagRepo;
    private final BookmarkTagRelRepository relRepo;
    private final BookmarkOwnership ownership;

    public BookmarkTagService(BookmarkTagRepository tagRepo,
                              BookmarkTagRelRepository relRepo,
                              BookmarkOwnership ownership) {
        this.tagRepo = tagRepo;
        this.relRepo = relRepo;
        this.ownership = ownership;
    }

    public List<BookmarkTagSummary> list() {
        String owner = ownership.owner();
        Map<Long, Long> counts = new HashMap<>();
        for (BookmarkTagRelEntity rel : relRepo.findAll()) {
            counts.merge(rel.getTagId(), 1L, Long::sum);
        }
        return tagRepo.findByOwnerIdOrderByNameAsc(owner).stream()
                .map(t -> new BookmarkTagSummary(t.getId(), t.getName(), counts.getOrDefault(t.getId(), 0L)))
                .toList();
    }

    /**
     * 创建标签。同名已存在时直接返回既有标签（幂等）——前端「边填边建标签」的路径依赖这个口径，
     * (owner_id, name) 唯一键因此不会在正常路径上被撞。
     */
    @Transactional
    public BookmarkTagSummary create(String rawName) {
        String owner = ownership.owner();
        String name = requireName(rawName);
        BookmarkTagEntity existing = tagRepo.findByOwnerIdAndName(owner, name).orElse(null);
        if (existing != null) {
            return new BookmarkTagSummary(existing.getId(), existing.getName(), refCount(existing.getId()));
        }
        BookmarkTagEntity t = new BookmarkTagEntity();
        t.setOwnerId(owner);
        t.setName(name);
        t.setCreatedAt(Instant.now());
        tagRepo.save(t);
        return new BookmarkTagSummary(t.getId(), t.getName(), 0L);
    }

    @Transactional
    public BookmarkTagSummary rename(Long id, String rawName) {
        String owner = ownership.owner();
        String name = requireName(rawName);
        BookmarkTagEntity t = mine(id, owner);
        tagRepo.findByOwnerIdAndName(owner, name)
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new DevMindException(ErrorCode.CONFLICT, "已存在同名标签：" + name);
                });
        t.setName(name);
        tagRepo.save(t);
        return new BookmarkTagSummary(t.getId(), t.getName(), refCount(t.getId()));
    }

    /** 删除标签仅解除关联，不动收藏（FR-03）。 */
    @Transactional
    public void delete(Long id) {
        String owner = ownership.owner();
        BookmarkTagEntity t = mine(id, owner);
        relRepo.deleteByTagId(t.getId());
        tagRepo.delete(t);
    }

    // ---------------- 内部 ----------------

    BookmarkTagEntity mine(Long id, String owner) {
        return tagRepo.findByIdAndOwnerId(id, owner)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, notFound(id)));
    }

    private long refCount(Long tagId) {
        return relRepo.findByTagId(tagId).size();
    }

    static String requireName(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "标签名必填");
        }
        if (name.length() > 64) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "标签名过长（≤64）：" + name);
        }
        return name;
    }

    private static String notFound(Long id) {
        return "标签不存在: " + id;
    }
}
