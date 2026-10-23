package com.devmind.knowledge.governance;

import com.devmind.knowledge.model.KnowledgeEntryEntity;
import com.devmind.knowledge.repo.KnowledgeEntryRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * CAP-85 FR-29：SimHash 近重复分组。16 位分 4 段建桶，只比较同桶条目；
 * 完全相同的指纹先并成一组，避免"同一篇复制 N 份"把桶退化成 O(N²)。
 * 只报告，不自动合并/删除。
 */
@Service
public class DuplicateDetector {

    private final KnowledgeEntryRepository entryRepo;

    public DuplicateDetector(KnowledgeEntryRepository entryRepo) {
        this.entryRepo = entryRepo;
    }

    public DuplicateReport report(Integer threshold, Integer maxEntries) {
        int maxDistance = clamp(threshold, 16, 3);
        int backfillLimit = maxEntries == null ? 2000 : Math.min(2000, Math.max(1, maxEntries));
        backfill(backfillLimit);

        List<KnowledgeEntryRepository.DuplicateRef> refs = entryRepo.findDuplicateRefs();
        List<Item> items = new ArrayList<>();
        for (KnowledgeEntryRepository.DuplicateRef ref : refs) {
            Long hash = SimHash.parse(ref.getSimHash());
            if (hash != null) {
                items.add(new Item(ref, hash));
            }
        }

        DistUnionFind uf = new DistUnionFind(items.size());
        // 同指纹先合并：桶里只留代表，防止大簇两两比较
        Map<Long, Integer> firstByHash = new HashMap<>();
        for (int i = 0; i < items.size(); i++) {
            Integer first = firstByHash.putIfAbsent(items.get(i).hash(), i);
            if (first != null) {
                uf.union(first, i, 0);
            }
        }
        List<Integer> reps = new ArrayList<>(firstByHash.values());

        Set<Long> checked = new HashSet<>();
        for (int band = 0; band < 4; band++) {
            Map<Integer, List<Integer>> buckets = new LinkedHashMap<>();
            for (int index : reps) {
                int key = SimHash.band(items.get(index).hash(), band);
                buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(index);
            }
            for (List<Integer> bucket : buckets.values()) {
                for (int a = 0; a < bucket.size(); a++) {
                    for (int b = a + 1; b < bucket.size(); b++) {
                        int i = bucket.get(a);
                        int j = bucket.get(b);
                        long pairKey = i < j ? ((long) i << 32) | (j & 0xffffffffL)
                                : ((long) j << 32) | (i & 0xffffffffL);
                        if (!checked.add(pairKey)) {
                            continue;
                        }
                        int distance = Long.bitCount(items.get(i).hash() ^ items.get(j).hash());
                        if (distance <= maxDistance) {
                            uf.union(i, j, distance);
                        }
                    }
                }
            }
        }

        Map<Integer, List<Item>> grouped = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            grouped.computeIfAbsent(uf.find(i), k -> new ArrayList<>()).add(items.get(i));
        }
        List<DuplicateReport.Group> groups = new ArrayList<>();
        for (Map.Entry<Integer, List<Item>> e : grouped.entrySet()) {
            if (e.getValue().size() < 2) {
                continue;
            }
            List<DuplicateReport.Item> members = e.getValue().stream()
                    .map(item -> item.view())
                    .toList();
            groups.add(new DuplicateReport.Group(members, uf.maxDistance(e.getKey())));
        }
        groups.sort(Comparator.comparingInt((DuplicateReport.Group g) -> g.entries().size()).reversed()
                .thenComparingInt(DuplicateReport.Group::maxDistance));

        return new DuplicateReport(Instant.now(), maxDistance, items.size(),
                entryRepo.countMissingSimHash(), groups);
    }

    /** 存量补算：单次最多 maxEntries 条，逐条加载避免整批 LOB 进内存。 */
    private void backfill(int maxEntries) {
        List<Long> ids = entryRepo.findMissingSimHashIds(PageRequest.of(0, maxEntries));
        for (Long id : ids) {
            KnowledgeEntryEntity entry = entryRepo.findById(id).orElse(null);
            if (entry == null) {
                continue;
            }
            String simHash = SimHash.of(entry.getContentMd());
            if (simHash != null) {
                entry.setSimHash(simHash);
                entryRepo.save(entry);
            }
        }
    }

    private static int clamp(Integer value, int max, int fallback) {
        if (value == null) {
            return fallback;
        }
        return Math.min(max, Math.max(0, value));
    }

    private record Item(KnowledgeEntryRepository.DuplicateRef ref, long hash) {
        DuplicateReport.Item view() {
            return new DuplicateReport.Item(ref.getId(), ref.getKbId(), ref.getName(),
                    ref.getDocType(), ref.getUpdatedAt(), ref.getSimHash());
        }
    }

    private static final class DistUnionFind {
        private final int[] parent;
        private final int[] rank;
        private final int[] maxDistance;

        DistUnionFind(int size) {
            parent = new int[size];
            rank = new int[size];
            maxDistance = new int[size];
            for (int i = 0; i < size; i++) {
                parent[i] = i;
            }
        }

        int find(int x) {
            while (parent[x] != x) {
                parent[x] = parent[parent[x]];
                x = parent[x];
            }
            return x;
        }

        void union(int a, int b, int distance) {
            int ra = find(a);
            int rb = find(b);
            if (ra == rb) {
                maxDistance[ra] = Math.max(maxDistance[ra], distance);
                return;
            }
            int root;
            if (rank[ra] < rank[rb]) {
                parent[ra] = rb;
                root = rb;
            } else if (rank[ra] > rank[rb]) {
                parent[rb] = ra;
                root = ra;
            } else {
                parent[rb] = ra;
                rank[ra]++;
                root = ra;
            }
            int other = root == ra ? rb : ra;
            maxDistance[root] = Math.max(Math.max(maxDistance[ra], maxDistance[rb]), distance);
            maxDistance[other] = 0;
        }

        int maxDistance(int x) {
            return maxDistance[find(x)];
        }
    }
}
