package com.devmind.knowledge.governance;

import java.time.Instant;
import java.util.List;

/**
 * CAP-85 FR-29 近重复报告。只报告不合并：分组 = SimHash 汉明距离连通分量，
 * {@code remainingWithoutHash} 提示还有多少条目没补算指纹（下次调用继续补）。
 */
public record DuplicateReport(Instant generatedAt, int threshold, int scanned,
                              long remainingWithoutHash, List<Group> groups) {

    public record Item(Long id, Long kbId, String name, String docType,
                       Instant updatedAt, String simHash) {
    }

    public record Group(List<Item> entries, int maxDistance) {
    }
}
