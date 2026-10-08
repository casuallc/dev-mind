package com.devmind.usage.repo;

/**
 * 分组聚合投影（JPQL 构造表达式，{@code group by} 维度列 + 合计）。key 可空 = 该维度未归属
 * （如 requirement_id IS NULL 的自由会话）。
 */
public record UsageGroupRow(String key, long count, long turnCount, double costUsd, long inputTokens,
                            long outputTokens, long cacheReadTokens, long cacheCreationTokens) {

    public UsageGroupRow plus(UsageGroupRow o) {
        return new UsageGroupRow(key, count + o.count, turnCount + o.turnCount, costUsd + o.costUsd,
                inputTokens + o.inputTokens, outputTokens + o.outputTokens,
                cacheReadTokens + o.cacheReadTokens, cacheCreationTokens + o.cacheCreationTokens);
    }
}
