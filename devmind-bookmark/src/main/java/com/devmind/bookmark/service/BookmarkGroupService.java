package com.devmind.bookmark.service;

import com.devmind.bookmark.dto.BookmarkGroupRequest;
import com.devmind.bookmark.dto.BookmarkGroupView;
import com.devmind.bookmark.model.BookmarkEntity;
import com.devmind.bookmark.model.BookmarkGroupEntity;
import com.devmind.bookmark.repo.BookmarkGroupRepository;
import com.devmind.bookmark.repo.BookmarkRepository;
import com.devmind.bookmark.repo.BookmarkShareRepository;
import com.devmind.bookmark.repo.BookmarkTagRelRepository;
import com.devmind.bookmark.repo.BookmarkAccountRepository;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * CAP-64 FR-02 分组：树状组织、转移、删除二选一档位。
 *
 * <p>删除档位（文档只定了组内收藏的处置，子分组按「上提」处理，见 {@link #delete}）：
 * 默认档 = 组内收藏落未分组、子分组上提为删除组之父；cascade=true = 整棵子树连收藏一起删。</p>
 */
@Service
public class BookmarkGroupService {

    private final BookmarkGroupRepository groupRepo;
    private final BookmarkRepository bookmarkRepo;
    private final BookmarkTagRelRepository relRepo;
    private final BookmarkAccountRepository accountRepo;
    private final BookmarkShareRepository shareRepo;
    private final BookmarkOwnership ownership;

    public BookmarkGroupService(BookmarkGroupRepository groupRepo,
                                BookmarkRepository bookmarkRepo,
                                BookmarkTagRelRepository relRepo,
                                BookmarkAccountRepository accountRepo,
                                BookmarkShareRepository shareRepo,
                                BookmarkOwnership ownership) {
        this.groupRepo = groupRepo;
        this.bookmarkRepo = bookmarkRepo;
        this.relRepo = relRepo;
        this.accountRepo = accountRepo;
        this.shareRepo = shareRepo;
        this.ownership = ownership;
    }

    // ---------------- 查询 ----------------

    /** 我的分组树（含每组的直接收藏数）。 */
    public List<BookmarkGroupView> tree() {
        String owner = ownership.owner();
        List<BookmarkGroupEntity> groups = groupRepo.findByOwnerIdOrderBySortOrderAscIdAsc(owner);
        return BookmarkGroups.buildTree(groups, countsByGroup(owner, groups));
    }

    /** 组名映射（分享/列表回显用）。 */
    Map<Long, String> names() {
        String owner = ownership.owner();
        Map<Long, String> out = new HashMap<>();
        for (BookmarkGroupEntity g : groupRepo.findByOwnerIdOrderBySortOrderAscIdAsc(owner)) {
            out.put(g.getId(), g.getName());
        }
        return out;
    }

    private Map<Long, Long> countsByGroup(String owner, List<BookmarkGroupEntity> groups) {
        Set<Long> ids = groups.stream().map(BookmarkGroupEntity::getId).collect(Collectors.toSet());
        Map<Long, Long> counts = new HashMap<>();
        for (BookmarkEntity b : bookmarkRepo.findByOwnerIdOrderBySortOrderAscCreatedAtDesc(owner)) {
            if (b.getGroupId() != null && ids.contains(b.getGroupId())) {
                counts.merge(b.getGroupId(), 1L, Long::sum);
            }
        }
        return counts;
    }

    // ---------------- 写入 ----------------

    @Transactional
    public BookmarkGroupView create(BookmarkGroupRequest req) {
        String owner = ownership.owner();
        BookmarkGroupEntity g = new BookmarkGroupEntity();
        g.setOwnerId(owner);
        g.setName(req.name().trim());
        g.setParentId(req.parentId());
        g.setSortOrder(req.sortOrder() == null ? 0 : req.sortOrder());
        Instant now = Instant.now();
        g.setCreatedAt(now);
        g.setUpdatedAt(now);
        requireParent(req.parentId(), owner, null);
        groupRepo.save(g);
        return new BookmarkGroupView(g.getId(), g.getParentId(), g.getName(), g.getSortOrder(), 0L, List.of());
    }

    @Transactional
    public BookmarkGroupView update(Long id, BookmarkGroupRequest req) {
        String owner = ownership.owner();
        BookmarkGroupEntity g = mine(id, owner);
        requireParent(req.parentId(), owner, id);
        g.setName(req.name().trim());
        g.setParentId(req.parentId());
        if (req.sortOrder() != null) {
            g.setSortOrder(req.sortOrder());
        }
        g.setUpdatedAt(Instant.now());
        groupRepo.save(g);
        return new BookmarkGroupView(g.getId(), g.getParentId(), g.getName(), g.getSortOrder(),
                bookmarkRepo.countByGroupId(id), List.of());
    }

    /**
     * 删除分组（FR-02 二选一）。
     *
     * <p>cascade=false（默认）：组内收藏落未分组、子分组上提为被删组之父、组自身的分享边撤销；
     * cascade=true：整棵子树（子分组 + 收藏 + 账号 + 标签关联 + 相关分享边）一并删除。</p>
     */
    @Transactional
    public void delete(Long id, boolean cascade) {
        String owner = ownership.owner();
        BookmarkGroupEntity target = mine(id, owner);
        List<BookmarkGroupEntity> all = groupRepo.findByOwnerIdOrderBySortOrderAscIdAsc(owner);
        Set<Long> subtree = BookmarkGroups.subtreeIds(id, all);

        List<BookmarkEntity> doomed = bookmarkRepo.findByOwnerIdOrderBySortOrderAscCreatedAtDesc(owner).stream()
                .filter(b -> b.getGroupId() != null && subtree.contains(b.getGroupId()))
                .toList();
        for (BookmarkEntity b : doomed) {
            if (cascade) {
                wipeBookmark(b.getId());
            } else if (id.equals(b.getGroupId())) {
                // 只把「被删组自己」的收藏落未分组；存活子分组里的收藏原地不动
                b.setGroupId(null);
                b.setUpdatedAt(Instant.now());
                bookmarkRepo.save(b);
            }
        }
        for (BookmarkGroupEntity g : all) {
            if (!subtree.contains(g.getId())) {
                continue;
            }
            if (cascade) {
                shareRepo.deleteByGroupId(g.getId());
                groupRepo.delete(g);
            } else if (!g.getId().equals(id)) {
                // 子分组上提
                g.setParentId(target.getParentId());
                g.setUpdatedAt(Instant.now());
                groupRepo.save(g);
            }
        }
        if (!cascade) {
            shareRepo.deleteByGroupId(id);
            groupRepo.delete(target);
        }
    }

    /** 收藏级联清盘：标签关联 + 账号 + 分享边 + 条目本体。 */
    void wipeBookmark(Long bookmarkId) {
        relRepo.deleteByBookmarkId(bookmarkId);
        accountRepo.deleteByBookmarkId(bookmarkId);
        shareRepo.deleteByBookmarkId(bookmarkId);
        bookmarkRepo.deleteById(bookmarkId);
    }

    // ---------------- 内部 ----------------

    /** 按 id 取我的分组；他人/不存在一律 404。 */
    BookmarkGroupEntity mine(Long id, String owner) {
        return groupRepo.findByIdAndOwnerId(id, owner)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, notFound(id)));
    }

    /** 父分组存在性 + 归属 + 不成环（改父级时不能移到自己的子树里）。 */
    private void requireParent(Long parentId, String owner, Long selfId) {
        if (parentId == null) {
            return;
        }
        if (parentId.equals(selfId)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "分组不能作为自己的父级");
        }
        mine(parentId, owner);
        if (selfId != null && BookmarkGroups.inSubtree(parentId, selfId, groupRepo.findByOwnerIdOrderBySortOrderAscIdAsc(owner))) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "不能把分组移动到它自己的子分组下");
        }
    }

    private static String notFound(Long id) {
        return "分组不存在: " + id;
    }
}
