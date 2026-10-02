package com.devmind.bookmark.service;

import com.devmind.bookmark.dto.BookmarkImportRequest;
import com.devmind.bookmark.dto.ImportResultView;
import com.devmind.bookmark.model.BookmarkEntity;
import com.devmind.bookmark.model.BookmarkGroupEntity;
import com.devmind.bookmark.model.BookmarkTagEntity;
import com.devmind.bookmark.model.BookmarkTagRelEntity;
import com.devmind.bookmark.repo.BookmarkGroupRepository;
import com.devmind.bookmark.repo.BookmarkRepository;
import com.devmind.bookmark.repo.BookmarkTagRelRepository;
import com.devmind.bookmark.repo.BookmarkTagRepository;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * CAP-64 FR-09 浏览器书签导入：前端已把 Netscape HTML 解析成结构化树，这里只做校验 + 落库。
 *
 * <p>口径（与 CAP-64 M3 实现口径一致）：</p>
 * <ul>
 *   <li>分组复用键 = (owner, parentId, name)——同父下同名分组复用不重建，重复导入同一文件幂等；</li>
 *   <li>去重 = owner 内完全相同 URL（normalize 后精确匹配，含批次内重复）跳过并计数，
 *       不覆盖既有条目任何字段；</li>
 *   <li>非法地址（非 http/https、缺主机名）跳过并计数，不阻塞整批；&gt;64 字符的标签名同样跳过；</li>
 *   <li>标题/描述/组名超列宽（256/1024/128）截断而不报错；</li>
 *   <li>上限 2000 条书签 / 8 层文件夹，超限抛 400——单事务回滚，不残留半截导入。</li>
 * </ul>
 */
@Service
public class BookmarkImportService {

    static final int MAX_BOOKMARKS = 2000;
    static final int MAX_DEPTH = 8;
    private static final int MAX_TITLE = 256;
    private static final int MAX_DESCRIPTION = 1024;
    private static final int MAX_GROUP_NAME = 128;
    private static final int MAX_TAG_NAME = 64;

    private final BookmarkRepository repo;
    private final BookmarkGroupRepository groupRepo;
    private final BookmarkTagRepository tagRepo;
    private final BookmarkTagRelRepository relRepo;
    private final BookmarkOwnership ownership;

    public BookmarkImportService(BookmarkRepository repo,
                                 BookmarkGroupRepository groupRepo,
                                 BookmarkTagRepository tagRepo,
                                 BookmarkTagRelRepository relRepo,
                                 BookmarkOwnership ownership) {
        this.repo = repo;
        this.groupRepo = groupRepo;
        this.tagRepo = tagRepo;
        this.relRepo = relRepo;
        this.ownership = ownership;
    }

    @Transactional
    public ImportResultView importTree(BookmarkImportRequest req) {
        String owner = ownership.owner();
        Stats stats = new Stats();
        Set<String> seenUrls = new HashSet<>();
        for (BookmarkEntity b : repo.findByOwnerIdOrderBySortOrderAscCreatedAtDesc(owner)) {
            seenUrls.add(b.getUrl());
        }
        Map<GroupKey, Long> groupIds = new HashMap<>();
        for (BookmarkGroupEntity g : groupRepo.findByOwnerIdOrderBySortOrderAscIdAsc(owner)) {
            groupIds.putIfAbsent(new GroupKey(g.getParentId(), g.getName()), g.getId());
        }
        Map<String, Long> tagIds = new HashMap<>();
        for (BookmarkTagEntity t : tagRepo.findByOwnerIdOrderByNameAsc(owner)) {
            tagIds.put(t.getName(), t.getId());
        }
        walk(req.nodes(), null, 1, owner, seenUrls, groupIds, tagIds, stats);
        return stats.view();
    }

    private void walk(List<BookmarkImportRequest.Node> nodes, Long groupId, int depth,
                      String owner, Set<String> seenUrls, Map<GroupKey, Long> groupIds,
                      Map<String, Long> tagIds, Stats stats) {
        if (nodes == null) {
            return;
        }
        int order = 0;
        for (BookmarkImportRequest.Node n : nodes) {
            if (n == null || n.type() == null) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "导入节点缺少类型（folder/bookmark）");
            }
            switch (n.type()) {
                case "folder" -> {
                    if (depth > MAX_DEPTH) {
                        throw new DevMindException(ErrorCode.BAD_REQUEST,
                                "文件夹层级超过上限（" + MAX_DEPTH + " 层）");
                    }
                    Long id = groupFor(n, groupId, owner, groupIds, stats);
                    walk(n.children(), id, depth + 1, owner, seenUrls, groupIds, tagIds, stats);
                }
                case "bookmark" -> importBookmark(n, groupId, order++, owner, seenUrls, tagIds, stats);
                default -> throw new DevMindException(ErrorCode.BAD_REQUEST, "未知节点类型：" + n.type());
            }
        }
    }

    /** 同父下同名分组复用，不存在才新建（重复导入幂等的关键）。 */
    private Long groupFor(BookmarkImportRequest.Node n, Long parentId, String owner,
                          Map<GroupKey, Long> groupIds, Stats stats) {
        String name = n.name() == null ? "" : n.name().trim();
        if (name.isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "文件夹名不能为空");
        }
        if (name.length() > MAX_GROUP_NAME) {
            name = name.substring(0, MAX_GROUP_NAME);
        }
        GroupKey key = new GroupKey(parentId, name);
        Long existing = groupIds.get(key);
        if (existing != null) {
            return existing;
        }
        BookmarkGroupEntity g = new BookmarkGroupEntity();
        g.setOwnerId(owner);
        g.setParentId(parentId);
        g.setName(name);
        g.setSortOrder(0);
        Instant now = Instant.now();
        g.setCreatedAt(now);
        g.setUpdatedAt(now);
        groupRepo.save(g);
        groupIds.put(key, g.getId());
        stats.createdGroups++;
        return g.getId();
    }

    private void importBookmark(BookmarkImportRequest.Node n, Long groupId, int order,
                                String owner, Set<String> seenUrls, Map<String, Long> tagIds, Stats stats) {
        stats.visited++;
        if (stats.visited > MAX_BOOKMARKS) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "单次导入书签超过上限（" + MAX_BOOKMARKS + " 条）");
        }
        String url;
        try {
            url = BookmarkUrls.normalize(n.url());
        } catch (DevMindException e) {
            stats.skippedInvalid++;
            return;
        }
        if (!seenUrls.add(url)) {
            stats.skippedDuplicates++;
            return;
        }
        BookmarkEntity e = new BookmarkEntity();
        e.setOwnerId(owner);
        e.setGroupId(groupId);
        String title = n.title() == null || n.title().trim().isEmpty() ? url : n.title().trim();
        e.setTitle(truncate(title, MAX_TITLE));
        e.setUrl(url);
        e.setDescription(n.description() == null || n.description().trim().isEmpty()
                ? null : truncate(n.description().trim(), MAX_DESCRIPTION));
        e.setSortOrder(order);
        e.setLastStatus(BookmarkEntity.STATUS_UNKNOWN);
        Instant now = Instant.now();
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        repo.save(e);
        attachTags(e.getId(), n.tags(), owner, tagIds);
        stats.createdBookmarks++;
    }

    /** Firefox TAGS 按名 get-or-create；空名/超长名跳过不阻塞导入。 */
    private void attachTags(Long bookmarkId, List<String> rawNames, String owner, Map<String, Long> tagIds) {
        if (rawNames == null) {
            return;
        }
        Set<Long> attached = new HashSet<>();
        for (String raw : rawNames) {
            String name = raw == null ? "" : raw.trim();
            if (name.isEmpty() || name.length() > MAX_TAG_NAME) {
                continue;
            }
            Long tagId = tagIds.get(name);
            if (tagId == null) {
                BookmarkTagEntity t = new BookmarkTagEntity();
                t.setOwnerId(owner);
                t.setName(name);
                t.setCreatedAt(Instant.now());
                tagRepo.save(t);
                tagId = t.getId();
                tagIds.put(name, tagId);
            }
            if (attached.add(tagId)) {
                BookmarkTagRelEntity rel = new BookmarkTagRelEntity();
                rel.setBookmarkId(bookmarkId);
                rel.setTagId(tagId);
                relRepo.save(rel);
            }
        }
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** 分组复用键（parentId 可空，record equals 对 null 安全）。 */
    private record GroupKey(Long parentId, String name) {
        GroupKey {
            Objects.requireNonNull(name, "name");
        }
    }

    private static final class Stats {
        int visited;
        int createdGroups;
        int createdBookmarks;
        int skippedDuplicates;
        int skippedInvalid;

        ImportResultView view() {
            return new ImportResultView(createdGroups, createdBookmarks, skippedDuplicates, skippedInvalid);
        }
    }
}
