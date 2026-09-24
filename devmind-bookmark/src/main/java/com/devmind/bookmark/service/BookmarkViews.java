package com.devmind.bookmark.service;

import com.devmind.bookmark.dto.BookmarkAccountView;
import com.devmind.bookmark.dto.BookmarkTagView;
import com.devmind.bookmark.dto.BookmarkView;
import com.devmind.bookmark.model.BookmarkAccountEntity;
import com.devmind.bookmark.model.BookmarkEntity;
import com.devmind.bookmark.model.BookmarkTagEntity;
import com.devmind.bookmark.model.BookmarkTagRelEntity;
import com.devmind.bookmark.repo.BookmarkAccountRepository;
import com.devmind.bookmark.repo.BookmarkTagRelRepository;
import com.devmind.bookmark.repo.BookmarkTagRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 收藏出参装配（批量，避免 N+1）：标签关联 + 账号（含脱敏口径）。
 * 收藏/分享两条链路共用，保证「分享视图剔除密码」与「主视图脱敏」口径只写一遍。
 */
@Component
class BookmarkViews {

    /** FR-05 固定掩码：出参永不回显密文，明文只能走 /secret 端点按次取 */
    static final String PASSWORD_MASK = "******";

    private final BookmarkTagRelRepository relRepo;
    private final BookmarkTagRepository tagRepo;
    private final BookmarkAccountRepository accountRepo;

    BookmarkViews(BookmarkTagRelRepository relRepo,
                  BookmarkTagRepository tagRepo,
                  BookmarkAccountRepository accountRepo) {
        this.relRepo = relRepo;
        this.tagRepo = tagRepo;
        this.accountRepo = accountRepo;
    }

    List<BookmarkView> assemble(List<BookmarkEntity> items, boolean maskPassword) {
        if (items.isEmpty()) {
            return List.of();
        }
        List<Long> ids = items.stream().map(BookmarkEntity::getId).toList();
        Map<Long, List<BookmarkTagView>> tags = tagsByBookmark(ids);
        Map<Long, List<BookmarkAccountView>> accounts = accountsByBookmark(ids, maskPassword);
        List<BookmarkView> out = new ArrayList<>(items.size());
        for (BookmarkEntity e : items) {
            out.add(toView(e,
                    tags.getOrDefault(e.getId(), List.of()),
                    accounts.getOrDefault(e.getId(), List.of())));
        }
        return out;
    }

    BookmarkView assembleOne(BookmarkEntity e, boolean maskPassword) {
        return assemble(List.of(e), maskPassword).get(0);
    }

    private static BookmarkView toView(BookmarkEntity e, List<BookmarkTagView> tags, List<BookmarkAccountView> accounts) {
        return new BookmarkView(
                String.valueOf(e.getId()),
                e.getGroupId(),
                e.getTitle(),
                e.getUrl(),
                e.getDescription(),
                e.getFaviconUrl(),
                e.getSortOrder(),
                e.getLastStatus(),
                e.getLastStatusCode(),
                e.getLastLatencyMs(),
                e.getLastCheckedAt(),
                e.getLastVisitedAt(),
                e.getCreatedAt(),
                e.getUpdatedAt(),
                tags,
                accounts);
    }

    private Map<Long, List<BookmarkTagView>> tagsByBookmark(List<Long> bookmarkIds) {
        List<BookmarkTagRelEntity> rels = relRepo.findByBookmarkIdIn(bookmarkIds);
        if (rels.isEmpty()) {
            return Map.of();
        }
        Map<Long, BookmarkTagEntity> tagById = tagRepo
                .findAllById(rels.stream().map(BookmarkTagRelEntity::getTagId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(BookmarkTagEntity::getId, Function.identity()));
        Map<Long, List<BookmarkTagView>> out = new HashMap<>();
        for (BookmarkTagRelEntity rel : rels) {
            BookmarkTagEntity t = tagById.get(rel.getTagId());
            if (t == null) {
                continue;
            }
            out.computeIfAbsent(rel.getBookmarkId(), k -> new ArrayList<>())
                    .add(new BookmarkTagView(t.getId(), t.getName()));
        }
        for (List<BookmarkTagView> list : out.values()) {
            list.sort(Comparator.comparing(BookmarkTagView::name));
        }
        return out;
    }

    private Map<Long, List<BookmarkAccountView>> accountsByBookmark(List<Long> bookmarkIds, boolean maskPassword) {
        List<BookmarkAccountEntity> accounts = accountRepo.findByBookmarkIdIn(bookmarkIds);
        Map<Long, List<BookmarkAccountView>> out = new LinkedHashMap<>();
        accounts.stream()
                .sorted(Comparator.comparing(BookmarkAccountEntity::getSortOrder)
                        .thenComparing(BookmarkAccountEntity::getId))
                .forEach(a -> out.computeIfAbsent(a.getBookmarkId(), k -> new ArrayList<>()).add(toAccountView(a, maskPassword)));
        return out;
    }

    /** 分享视图（maskPassword=false）直接给 null → 借 @JsonInclude(NON_NULL) 把 passwordMasked 字段整个剔除。 */
    static BookmarkAccountView toAccountView(BookmarkAccountEntity a, boolean maskPassword) {
        boolean hasPassword = a.getPasswordEnc() != null && !a.getPasswordEnc().isBlank();
        return new BookmarkAccountView(
                String.valueOf(a.getId()),
                a.getLabel(),
                a.getUsername(),
                maskPassword && hasPassword ? PASSWORD_MASK : null,
                hasPassword,
                a.getNote(),
                a.getSortOrder());
    }
}
