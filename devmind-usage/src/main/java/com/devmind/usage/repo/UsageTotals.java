package com.devmind.usage.repo;

/**
 * 聚合合计投影（JPQL 构造表达式）。两源（sessions / chat_sessions）各查一份，服务层相加。
 */
public record UsageTotals(long count, long turnCount, double costUsd, long inputTokens, long outputTokens,
                          long cacheReadTokens, long cacheCreationTokens) {

    public static final UsageTotals ZERO = new UsageTotals(0, 0, 0, 0, 0, 0, 0);

    public UsageTotals plus(UsageTotals o) {
        return new UsageTotals(count + o.count, turnCount + o.turnCount, costUsd + o.costUsd,
                inputTokens + o.inputTokens, outputTokens + o.outputTokens,
                cacheReadTokens + o.cacheReadTokens, cacheCreationTokens + o.cacheCreationTokens);
    }
}
