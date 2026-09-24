package com.devmind.bookmark.service;

import com.devmind.bookmark.dto.BookmarkGroupView;
import com.devmind.bookmark.model.BookmarkGroupEntity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CAP-64 FR-02 分组树的纯函数工具：组树、子树 id 集合、父子环判定。
 * 无状态无依赖，供分组/收藏/分享三处共用（含分享视图里「父分组未分享」的孤儿节点上提）。
 */
final class BookmarkGroups {

    private BookmarkGroups() {
    }

    /**
     * 组树。根节点 = parentId 为空，或其父不在给定集合内（分享场景下父分组未分享时上提为根，不丢节点）。
     */
    static List<BookmarkGroupView> buildTree(List<BookmarkGroupEntity> groups, Map<Long, Long> counts) {
        Set<Long> ids = new HashSet<>();
        for (BookmarkGroupEntity g : groups) {
            ids.add(g.getId());
        }
        Map<Long, List<BookmarkGroupEntity>> byParent = new LinkedHashMap<>();
        for (BookmarkGroupEntity g : groups) {
            Long parent = g.getParentId();
            if (parent != null && !ids.contains(parent)) {
                parent = null;
            }
            byParent.computeIfAbsent(parent, k -> new ArrayList<>()).add(g);
        }
        return childrenOf(null, byParent, counts);
    }

    private static List<BookmarkGroupView> childrenOf(Long parentId,
                                                      Map<Long, List<BookmarkGroupEntity>> byParent,
                                                      Map<Long, Long> counts) {
        List<BookmarkGroupView> out = new ArrayList<>();
        for (BookmarkGroupEntity g : byParent.getOrDefault(parentId, List.of())) {
            out.add(new BookmarkGroupView(g.getId(), g.getParentId(), g.getName(), g.getSortOrder(),
                    counts.getOrDefault(g.getId(), 0L),
                    childrenOf(g.getId(), byParent, counts)));
        }
        return out;
    }

    /** rootId 及其全部后代的 id 集合（含自身；后代收集是环安全的）。 */
    static Set<Long> subtreeIds(Long rootId, Collection<BookmarkGroupEntity> all) {
        Map<Long, List<Long>> children = new HashMap<>();
        for (BookmarkGroupEntity g : all) {
            children.computeIfAbsent(g.getParentId(), k -> new ArrayList<>()).add(g.getId());
        }
        Set<Long> out = new LinkedHashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(rootId);
        while (!queue.isEmpty()) {
            Long cur = queue.poll();
            if (!out.add(cur)) {
                continue;
            }
            queue.addAll(children.getOrDefault(cur, List.of()));
        }
        return out;
    }

    /** 把 id 归到根 id 上：id 是 rootId 本身或其后代。 */
    static boolean inSubtree(Long id, Long rootId, Collection<BookmarkGroupEntity> all) {
        return id != null && subtreeIds(rootId, all).contains(id);
    }
}
