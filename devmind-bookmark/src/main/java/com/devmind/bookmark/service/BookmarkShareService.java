package com.devmind.bookmark.service;

import com.devmind.auth.repo.UserRepository;
import com.devmind.bookmark.dto.BookmarkGroupView;
import com.devmind.bookmark.dto.BookmarkShareRequest;
import com.devmind.bookmark.dto.BookmarkShareView;
import com.devmind.bookmark.dto.BookmarkView;
import com.devmind.bookmark.dto.CopySharedRequest;
import com.devmind.bookmark.dto.SharedWithMeView;
import com.devmind.bookmark.model.BookmarkEntity;
import com.devmind.bookmark.model.BookmarkGroupEntity;
import com.devmind.bookmark.model.BookmarkShareEntity;
import com.devmind.bookmark.model.BookmarkTagEntity;
import com.devmind.bookmark.model.BookmarkTagRelEntity;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * CAP-64 FR-07 分享：平台内指定用户、只读引用。源数据变更实时反映（每次读取都从源表现算），
 * 撤销即不可见（删边），接收方不可改不可再分享（主端点 owner 强制不受分享影响），可复制为自己的数据。
 *
 * <p>分享是 owner 强制的**唯一例外通道**：接收方只经 {@code /api/bookmarks/shared-with-me} 命名空间读取。</p>
 */
@Service
public class BookmarkShareService {

    private final BookmarkShareRepository shareRepo;
    private final BookmarkRepository bookmarkRepo;
    private final BookmarkGroupRepository groupRepo;
    private final BookmarkTagRepository tagRepo;
    private final BookmarkTagRelRepository relRepo;
    private final UserRepository userRepo;
    private final BookmarkOwnership ownership;
    private final BookmarkViews views;

    public BookmarkShareService(BookmarkShareRepository shareRepo,
                                BookmarkRepository bookmarkRepo,
                                BookmarkGroupRepository groupRepo,
                                BookmarkTagRepository tagRepo,
                                BookmarkTagRelRepository relRepo,
                                UserRepository userRepo,
                                BookmarkOwnership ownership,
                                BookmarkViews views) {
        this.shareRepo = shareRepo;
        this.bookmarkRepo = bookmarkRepo;
        this.groupRepo = groupRepo;
        this.tagRepo = tagRepo;
        this.relRepo = relRepo;
        this.userRepo = userRepo;
        this.ownership = ownership;
        this.views = views;
    }

    // ---------------- 我发出的 ----------------

    public List<BookmarkShareView> listMine() {
        String me = ownership.owner();
        List<BookmarkShareEntity> shares = shareRepo.findByOwnerIdOrderByCreatedAtDesc(me);
        if (shares.isEmpty()) {
            return List.of();
        }
        Map<Long, String> groupNames = new HashMap<>();
        for (BookmarkGroupEntity g : groupRepo.findByOwnerIdOrderBySortOrderAscIdAsc(me)) {
            groupNames.put(g.getId(), g.getName());
        }
        Map<Long, String> titles = new HashMap<>();
        for (BookmarkEntity b : bookmarkRepo.findByOwnerIdOrderBySortOrderAscCreatedAtDesc(me)) {
            titles.put(b.getId(), b.getTitle());
        }
        return shares.stream()
                .map(s -> new BookmarkShareView(
                        s.getId(),
                        s.getBookmarkId(),
                        s.getBookmarkId() == null ? null : titles.get(s.getBookmarkId()),
                        s.getGroupId(),
                        s.getGroupId() == null ? null : groupNames.get(s.getGroupId()),
                        s.getTargetUser(),
                        s.getCreatedAt()))
                .toList();
    }

    @Transactional
    public BookmarkShareView create(BookmarkShareRequest req) {
        String me = ownership.owner();
        boolean byBookmark = req.bookmarkId() != null;
        boolean byGroup = req.groupId() != null;
        if (byBookmark == byGroup) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "分享对象必须是「一条收藏」或「一个分组」二选一");
        }
        String target = req.targetUser().trim();
        if (target.equals(me)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "不能分享给自己");
        }
        userRepo.findByUsername(target)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "用户不存在: " + target));

        if (byBookmark) {
            BookmarkEntity b = bookmarkRepo.findByIdAndOwnerId(req.bookmarkId(), me)
                    .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "收藏不存在: " + req.bookmarkId()));
            shareRepo.findByOwnerIdAndTargetUserAndBookmarkId(me, target, b.getId()).ifPresent(s -> {
                throw new DevMindException(ErrorCode.CONFLICT, "该收藏已分享给 " + target);
            });
        } else {
            BookmarkGroupEntity g = groupRepo.findByIdAndOwnerId(req.groupId(), me)
                    .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "分组不存在: " + req.groupId()));
            shareRepo.findByOwnerIdAndTargetUserAndGroupId(me, target, g.getId()).ifPresent(s -> {
                throw new DevMindException(ErrorCode.CONFLICT, "该分组已分享给 " + target);
            });
        }

        BookmarkShareEntity s = new BookmarkShareEntity();
        s.setOwnerId(me);
        s.setBookmarkId(req.bookmarkId());
        s.setGroupId(req.groupId());
        s.setTargetUser(target);
        s.setCreatedAt(Instant.now());
        shareRepo.save(s);
        return listMine().stream().filter(v -> v.id().equals(s.getId())).findFirst()
                .orElse(new BookmarkShareView(s.getId(), s.getBookmarkId(), null, s.getGroupId(), null, target, s.getCreatedAt()));
    }

    @Transactional
    public void delete(Long id) {
        String me = ownership.owner();
        BookmarkShareEntity s = shareRepo.findByIdAndOwnerId(id, me)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "分享不存在: " + id));
        shareRepo.delete(s);
    }

    // ---------------- 我收到的 ----------------

    /** 与我分享的内容，按分享者分组（v1 单分享者视图）。密码字段整体剔除。 */
    public List<SharedWithMeView> sharedWithMe() {
        String me = ownership.owner();
        List<BookmarkShareEntity> shares = shareRepo.findByTargetUserOrderByCreatedAtDesc(me);
        if (shares.isEmpty()) {
            return List.of();
        }
        Map<String, List<BookmarkShareEntity>> byOwner = new LinkedHashMap<>();
        for (BookmarkShareEntity s : shares) {
            byOwner.computeIfAbsent(s.getOwnerId(), k -> new ArrayList<>()).add(s);
        }
        List<SharedWithMeView> out = new ArrayList<>();
        for (Map.Entry<String, List<BookmarkShareEntity>> entry : byOwner.entrySet()) {
            String owner = entry.getKey();
            List<BookmarkGroupEntity> allGroups = groupRepo.findByOwnerIdOrderBySortOrderAscIdAsc(owner);
            Set<Long> sharedGroupIds = new LinkedHashSet<>();
            for (BookmarkShareEntity s : entry.getValue()) {
                if (s.getGroupId() != null) {
                    sharedGroupIds.addAll(BookmarkGroups.subtreeIds(s.getGroupId(), allGroups));
                }
            }
            List<BookmarkGroupEntity> sharedGroups = allGroups.stream()
                    .filter(g -> sharedGroupIds.contains(g.getId()))
                    .toList();
            List<BookmarkEntity> visible = visibleBookmarks(owner, entry.getValue(), sharedGroupIds);
            Map<Long, Long> counts = new HashMap<>();
            for (BookmarkEntity b : visible) {
                if (b.getGroupId() != null) {
                    counts.merge(b.getGroupId(), 1L, Long::sum);
                }
            }
            out.add(new SharedWithMeView(owner,
                    BookmarkGroups.buildTree(sharedGroups, counts),
                    views.assemble(visible, false)));
        }
        return out;
    }

    /** FR-07 复制分享来的收藏为自己的：深拷贝条目 + 标签名，不含账号密码。 */
    @Transactional
    public BookmarkView copy(CopySharedRequest req) {
        String me = ownership.owner();
        if (req.bookmarkId() == null) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "缺少来源收藏 id");
        }
        if (!visibleBookmarkIds(me).contains(req.bookmarkId())) {
            throw new DevMindException(ErrorCode.NOT_FOUND, "收藏不存在: " + req.bookmarkId());
        }
        BookmarkEntity src = bookmarkRepo.findById(req.bookmarkId())
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "收藏不存在: " + req.bookmarkId()));

        Long targetGroup = null;
        if (req.groupId() != null) {
            targetGroup = groupRepo.findByIdAndOwnerId(req.groupId(), me)
                    .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "分组不存在: " + req.groupId()))
                    .getId();
        }

        Instant now = Instant.now();
        BookmarkEntity copy = new BookmarkEntity();
        copy.setOwnerId(me);
        copy.setGroupId(targetGroup);
        copy.setTitle(src.getTitle());
        copy.setUrl(src.getUrl());
        copy.setDescription(src.getDescription());
        copy.setFaviconUrl(src.getFaviconUrl());
        copy.setSortOrder(src.getSortOrder());
        // 复制来的条目是全新入口，探测结论不跟着走（源站的可用性不该冒充我的）
        copy.setLastStatus(BookmarkEntity.STATUS_UNKNOWN);
        copy.setCreatedAt(now);
        copy.setUpdatedAt(now);
        bookmarkRepo.save(copy);

        copyTags(me, src.getId(), copy.getId());
        return views.assembleOne(copy, true);
    }

    // ---------------- 内部 ----------------

    /** 标签按**名字**复制：我的空间里没有同名标签就建一个，已有的直接复用。 */
    private void copyTags(String me, Long sourceBookmarkId, Long targetBookmarkId) {
        List<BookmarkTagRelEntity> rels = relRepo.findByBookmarkId(sourceBookmarkId);
        if (rels.isEmpty()) {
            return;
        }
        Map<Long, BookmarkTagEntity> sourceTags = tagRepo
                .findAllById(rels.stream().map(BookmarkTagRelEntity::getTagId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(BookmarkTagEntity::getId, Function.identity()));
        for (BookmarkTagRelEntity rel : rels) {
            BookmarkTagEntity srcTag = sourceTags.get(rel.getTagId());
            if (srcTag == null) {
                continue;
            }
            BookmarkTagEntity mine = tagRepo.findByOwnerIdAndName(me, srcTag.getName())
                    .orElseGet(() -> {
                        BookmarkTagEntity t = new BookmarkTagEntity();
                        t.setOwnerId(me);
                        t.setName(srcTag.getName());
                        t.setCreatedAt(Instant.now());
                        return tagRepo.save(t);
                    });
            BookmarkTagRelEntity nr = new BookmarkTagRelEntity();
            nr.setBookmarkId(targetBookmarkId);
            nr.setTagId(mine.getId());
            relRepo.save(nr);
        }
    }

    /** 我可见的分享收藏 id 集合（直接分享 + 分组分享的子分组展开）。 */
    private Set<Long> visibleBookmarkIds(String me) {
        List<BookmarkShareEntity> shares = shareRepo.findByTargetUserOrderByCreatedAtDesc(me);
        if (shares.isEmpty()) {
            return Set.of();
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (BookmarkShareEntity s : shares) {
            if (s.getBookmarkId() != null) {
                ids.add(s.getBookmarkId());
            }
        }
        // 分组分享：按分享者逐人展开（子分组一起算，FR-07「含组内全部收藏（含子分组）」）
        Map<String, List<BookmarkShareEntity>> byOwner = new HashMap<>();
        for (BookmarkShareEntity s : shares) {
            if (s.getGroupId() != null) {
                byOwner.computeIfAbsent(s.getOwnerId(), k -> new ArrayList<>()).add(s);
            }
        }
        for (Map.Entry<String, List<BookmarkShareEntity>> e : byOwner.entrySet()) {
            Set<Long> groupIds = new LinkedHashSet<>();
            for (BookmarkShareEntity s : e.getValue()) {
                groupIds.add(s.getGroupId());
            }
            List<BookmarkGroupEntity> allGroups = groupRepo.findByOwnerIdOrderBySortOrderAscIdAsc(e.getKey());
            Set<Long> subtree = new LinkedHashSet<>();
            for (Long gid : groupIds) {
                subtree.addAll(BookmarkGroups.subtreeIds(gid, allGroups));
            }
            ids.addAll(visibleBookmarks(e.getKey(), e.getValue(), subtree).stream().map(BookmarkEntity::getId).toList());
        }
        return ids;
    }

    /**
     * 分享者 owner 下、由这组分享边可见的收藏（保持分享者自己的排序）。
     *
     * @param sharedGroupIds 已展开的分组 id 集合（含子分组）
     */
    private List<BookmarkEntity> visibleBookmarks(String owner, List<BookmarkShareEntity> shares, Set<Long> sharedGroupIds) {
        Set<Long> directIds = new LinkedHashSet<>();
        for (BookmarkShareEntity s : shares) {
            if (s.getBookmarkId() != null) {
                directIds.add(s.getBookmarkId());
            }
        }
        return bookmarkRepo.findByOwnerIdOrderBySortOrderAscCreatedAtDesc(owner).stream()
                .filter(b -> directIds.contains(b.getId())
                        || (b.getGroupId() != null && sharedGroupIds.contains(b.getGroupId())))
                .toList();
    }
}
