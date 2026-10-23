package com.devmind.knowledge.sync;

/** CAP-85 FR-27：一次同步的结果统计；watermark 为下一次增量起点。 */
public record SyncOutcome(String watermark, int created, int updated, int unchanged,
                          int deprecated, int failed) {

    public static SyncOutcome empty(String watermark) {
        return new SyncOutcome(watermark, 0, 0, 0, 0, 0);
    }
}
