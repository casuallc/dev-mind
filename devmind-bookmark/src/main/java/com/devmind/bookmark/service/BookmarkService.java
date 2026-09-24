package com.devmind.bookmark.service;

import com.devmind.bookmark.config.BookmarkCipher;
import com.devmind.bookmark.dto.BookmarkAccountRequest;
import com.devmind.bookmark.dto.BookmarkRequest;
import com.devmind.bookmark.dto.BookmarkView;
import com.devmind.bookmark.dto.MoveBookmarksRequest;
import com.devmind.bookmark.dto.ProbeResultView;
import com.devmind.bookmark.dto.SecretView;
import com.devmind.bookmark.model.BookmarkAccountEntity;
import com.devmind.bookmark.model.BookmarkEntity;
import com.devmind.bookmark.model.BookmarkTagEntity;
import com.devmind.bookmark.model.BookmarkTagRelEntity;
import com.devmind.bookmark.repo.BookmarkAccountRepository;
import com.devmind.bookmark.repo.BookmarkGroupRepository;
import com.devmind.bookmark.repo.BookmarkRepository;
import com.devmind.bookmark.repo.BookmarkShareRepository;
import com.devmind.bookmark.repo.BookmarkTagRelRepository;
import com.devmind.bookmark.repo.BookmarkTagRepository;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * CAP-64 FR-01/05/06 收藏条目：CRUD、批量转移分组、最近访问、账号密文按次取。
 *
 * <p>归属口径（FR-08）：读 = owner 或 ADMIN（排障只读），写 = 仅 owner；越权一律 404 且文案与
 * 「不存在」逐字一致（不暴露存在性）。</p>
 */
@Service
public class BookmarkService {

    private final BookmarkRepository repo;
    private final BookmarkGroupRepository groupRepo;
    private final BookmarkTagRepository tagRepo;
    private final BookmarkTagRelRepository relRepo;
    private final BookmarkAccountRepository accountRepo;
    private final BookmarkShareRepository shareRepo;
    private final BookmarkCipher cipher;
    private final BookmarkOwnership ownership;
    private final BookmarkViews views;
    private final BookmarkProbeService probeService;

    public BookmarkService(BookmarkRepository repo,
                           BookmarkGroupRepository groupRepo,
                           BookmarkTagRepository tagRepo,
                           BookmarkTagRelRepository relRepo,
                           BookmarkAccountRepository accountRepo,
                           BookmarkShareRepository shareRepo,
                           BookmarkCipher cipher,
                           BookmarkOwnership ownership,
                           BookmarkViews views,
                           BookmarkProbeService probeService) {
        this.repo = repo;
        this.groupRepo = groupRepo;
        this.tagRepo = tagRepo;
        this.relRepo = relRepo;
        this.accountRepo = accountRepo;
        this.shareRepo = shareRepo;
        this.cipher = cipher;
        this.ownership = ownership;
        this.views = views;
        this.probeService = probeService;
    }

    // ---------------- 查询 ----------------

    /**
     * 我的收藏列表。筛选口径：
     * <ul>
     *   <li>{@code groupId} = 该分组**及其子分组**内的收藏（点树节点看到整棵子树，符合树状组织的直觉）；</li>
     *   <li>{@code ungrouped=true} = 未分组（与 groupId 互斥，优先级更高）；</li>
     *   <li>{@code tagIds} 多标签为**与**语义（同时挂着所选全部标签）；</li>
     *   <li>{@code keyword} 命中标题/URL/描述；{@code status} ∈ UNKNOWN/OK/FAIL（ALL 或空 = 不筛）。</li>
     * </ul>
     */
    public List<BookmarkView> list(Long groupId, Boolean ungrouped, List<Long> tagIds, String keyword, String status) {
        String owner = ownership.owner();
        List<BookmarkEntity> items = repo.findByOwnerIdOrderBySortOrderAscCreatedAtDesc(owner);

        if (Boolean.TRUE.equals(ungrouped)) {
            items = items.stream().filter(b -> b.getGroupId() == null).toList();
        } else if (groupId != null) {
            Set<Long> scope = BookmarkGroups.subtreeIds(groupId, groupRepo.findByOwnerIdOrderBySortOrderAscIdAsc(owner));
            items = items.stream()
                    .filter(b -> b.getGroupId() != null && scope.contains(b.getGroupId()))
                    .toList();
        }
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            String want = status.trim().toUpperCase(Locale.ROOT);
            items = items.stream().filter(b -> want.equals(b.getLastStatus())).toList();
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim().toLowerCase(Locale.ROOT);
            items = items.stream()
                    .filter(b -> contains(b.getTitle(), kw) || contains(b.getUrl(), kw) || contains(b.getDescription(), kw))
                    .toList();
        }
        if (tagIds != null && !tagIds.isEmpty()) {
            items = filterByTags(items, new LinkedHashSet<>(tagIds));
        }
        return views.assemble(items, true);
    }

    /** 单条详情（owner 或 ADMIN 可读）。 */
    public BookmarkView get(Long id) {
        return views.assembleOne(requireReadable(id), true);
    }

    // ---------------- 写入 ----------------

    @Transactional
    public BookmarkView create(BookmarkRequest req) {
        String owner = ownership.owner();
        BookmarkEntity e = new BookmarkEntity();
        e.setOwnerId(owner);
        e.setLastStatus(BookmarkEntity.STATUS_UNKNOWN);
        applyFields(e, req, owner);
        Instant now = Instant.now();
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        repo.save(e);
        replaceTags(e.getId(), req.tagIds(), owner);
        replaceAccounts(e.getId(), req.accounts());
        return views.assembleOne(e, true);
    }

    /** 全量编辑：tags / accounts 整组替换（未出现的账号即删除）。 */
    @Transactional
    public BookmarkView update(Long id, BookmarkRequest req) {
        String owner = ownership.owner();
        BookmarkEntity e = requireOwned(id);
        applyFields(e, req, owner);
        e.setUpdatedAt(Instant.now());
        repo.save(e);
        replaceTags(e.getId(), req.tagIds(), owner);
        replaceAccounts(e.getId(), req.accounts());
        return views.assembleOne(e, true);
    }

    @Transactional
    public void delete(Long id) {
        BookmarkEntity e = requireOwned(id);
        relRepo.deleteByBookmarkId(e.getId());
        accountRepo.deleteByBookmarkId(e.getId());
        shareRepo.deleteByBookmarkId(e.getId());
        repo.delete(e);
    }

    /** FR-02 批量转移分组（groupId=null 即移到未分组）。返回实际移动条数。 */
    @Transactional
    public int move(MoveBookmarksRequest req) {
        String owner = ownership.owner();
        Long target = requireGroup(req.groupId(), owner);
        List<BookmarkEntity> items = req.ids().stream().distinct().map(this::requireOwned).toList();
        Instant now = Instant.now();
        for (BookmarkEntity e : items) {
            e.setGroupId(target);
            e.setUpdatedAt(now);
            repo.save(e);
        }
        return items.size();
    }

    /** FR-06 打开动作落 last_visited_at（前端打开同时调用，失败静默；不另建访问日志表）。 */
    @Transactional
    public void visit(Long id) {
        BookmarkEntity e = requireOwned(id);
        e.setLastVisitedAt(Instant.now());
        repo.save(e);
    }

    /** FR-05 按次取账号明文（仅 owner，绝不进列表出参）。 */
    public SecretView secret(Long id, String accountId) {
        BookmarkEntity e = requireOwned(id);
        Long aid = parseAccountId(accountId);
        BookmarkAccountEntity acc = accountRepo.findByIdAndBookmarkId(aid, e.getId())
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "账号不存在: " + accountId));
        if (acc.getPasswordEnc() == null || acc.getPasswordEnc().isBlank()) {
            return new SecretView(null);
        }
        return new SecretView(cipher.decrypt(acc.getPasswordEnc()));
    }

    /** FR-04 单条探测（同步返回结果）。 */
    public ProbeResultView probe(Long id) {
        return probeService.probe(id);
    }

    // ---------------- 归属 ----------------

    /** 读：owner 或 ADMIN；否则按不存在处理。 */
    BookmarkEntity requireReadable(Long id) {
        BookmarkEntity e = repo.findById(id)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, notFound(id)));
        String actor = ownership.owner();
        if (!actor.equals(e.getOwnerId()) && !ownership.isAdmin()) {
            throw new DevMindException(ErrorCode.NOT_FOUND, notFound(id));
        }
        return e;
    }

    /** 写：仅 owner；ADMIN 不可写（FR-08）。 */
    BookmarkEntity requireOwned(Long id) {
        BookmarkEntity e = repo.findById(id)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, notFound(id)));
        if (!ownership.owner().equals(e.getOwnerId())) {
            throw new DevMindException(ErrorCode.NOT_FOUND, notFound(id));
        }
        return e;
    }

    private static String notFound(Long id) {
        return "收藏不存在: " + id;
    }

    // ---------------- 内部 ----------------

    private void applyFields(BookmarkEntity e, BookmarkRequest req, String owner) {
        e.setTitle(req.title().trim());
        e.setUrl(BookmarkUrls.normalize(req.url()));
        e.setDescription(blankToNull(req.description()));
        e.setFaviconUrl(blankToNull(req.faviconUrl()));
        if (req.sortOrder() != null) {
            e.setSortOrder(req.sortOrder());
        }
        e.setGroupId(requireGroup(req.groupId(), owner));
    }

    /** 目标分组必须是我自己的；null = 未分组。 */
    private Long requireGroup(Long groupId, String owner) {
        if (groupId == null) {
            return null;
        }
        groupRepo.findByIdAndOwnerId(groupId, owner)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "分组不存在: " + groupId));
        return groupId;
    }

    /** 标签整组替换：先清后插，显式 flush 保证 DELETE 先于 INSERT 落库（唯一键不会被自己撞）。 */
    private void replaceTags(Long bookmarkId, List<Long> tagIds, String owner) {
        relRepo.deleteByBookmarkId(bookmarkId);
        relRepo.flush();
        if (tagIds == null || tagIds.isEmpty()) {
            return;
        }
        for (Long tagId : new LinkedHashSet<>(tagIds)) {
            BookmarkTagEntity t = tagRepo.findByIdAndOwnerId(tagId, owner)
                    .orElseThrow(() -> new DevMindException(ErrorCode.BAD_REQUEST, "标签不存在或不属于你: " + tagId));
            BookmarkTagRelEntity rel = new BookmarkTagRelEntity();
            rel.setBookmarkId(bookmarkId);
            rel.setTagId(t.getId());
            relRepo.save(rel);
        }
    }

    /**
     * 账号整组提交（FR-05）：带 id 的更新、无 id 的新建、未出现的删除。
     * password 为 null 表示不修改（编辑态留空语义），clearPassword=true 才清除已有密码。
     */
    private void replaceAccounts(Long bookmarkId, List<BookmarkAccountRequest> reqs) {
        List<BookmarkAccountEntity> existing = accountRepo.findByBookmarkIdOrderBySortOrderAscIdAsc(bookmarkId);
        Map<Long, BookmarkAccountEntity> byId = new HashMap<>();
        for (BookmarkAccountEntity a : existing) {
            byId.put(a.getId(), a);
        }
        Set<Long> kept = new HashSet<>();
        int index = 0;
        if (reqs != null) {
            for (BookmarkAccountRequest r : reqs) {
                BookmarkAccountEntity acc;
                if (r.id() != null && !r.id().isBlank()) {
                    Long aid = parseAccountId(r.id());
                    acc = byId.get(aid);
                    if (acc == null) {
                        throw new DevMindException(ErrorCode.BAD_REQUEST, "账号不存在: " + r.id());
                    }
                    kept.add(aid);
                } else {
                    acc = new BookmarkAccountEntity();
                    acc.setBookmarkId(bookmarkId);
                }
                acc.setLabel(requireLabel(r.label()));
                acc.setUsername(blankToNull(r.username()));
                acc.setNote(blankToNull(r.note()));
                acc.setSortOrder(r.sortOrder() == null ? index : r.sortOrder());
                if (Boolean.TRUE.equals(r.clearPassword())) {
                    acc.setPasswordEnc(null);
                } else if (r.password() != null && !r.password().isEmpty()) {
                    acc.setPasswordEnc(cipher.encrypt(r.password()));
                }
                accountRepo.save(acc);
                index++;
            }
        }
        for (BookmarkAccountEntity old : existing) {
            if (!kept.contains(old.getId())) {
                accountRepo.delete(old);
            }
        }
    }

    private List<BookmarkEntity> filterByTags(List<BookmarkEntity> items, Set<Long> tagIds) {
        if (items.isEmpty()) {
            return items;
        }
        Map<Long, Set<Long>> byBookmark = new HashMap<>();
        List<Long> ids = new ArrayList<>(items.size());
        for (BookmarkEntity b : items) {
            ids.add(b.getId());
        }
        for (BookmarkTagRelEntity rel : relRepo.findByBookmarkIdIn(ids)) {
            byBookmark.computeIfAbsent(rel.getBookmarkId(), k -> new HashSet<>()).add(rel.getTagId());
        }
        return items.stream()
                .filter(b -> byBookmark.getOrDefault(b.getId(), Set.of()).containsAll(tagIds))
                .toList();
    }

    private static boolean contains(String haystack, String lowerKeyword) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(lowerKeyword);
    }

    private static String requireLabel(String raw) {
        String label = raw == null ? "" : raw.trim();
        if (label.isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "账号用途（label）必填");
        }
        return label;
    }

    private static String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** 账号 id 非法一律按不存在处理（不泄漏格式细节）。 */
    private static Long parseAccountId(String raw) {
        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException e) {
            throw new DevMindException(ErrorCode.NOT_FOUND, "账号不存在: " + raw);
        }
    }
}
