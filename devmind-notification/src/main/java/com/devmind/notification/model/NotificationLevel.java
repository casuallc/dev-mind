package com.devmind.notification.model;

/**
 * CAP-06 FR-02 通知分级。
 * <pre>
 * P0 立即推：等待授权/输入、执行失败 → 浏览器通知 + 声音 + 外部通道（Bark/企微）；
 * P1 聚合推：任务完成等 → 浏览器通知，多条合并；
 * P2 静默进中心：里程碑、沉淀提议等。
 * </pre>
 */
public enum NotificationLevel {
    P0(0),
    P1(1),
    P2(2);

    private final int rank;

    NotificationLevel(int rank) {
        this.rank = rank;
    }

    /** 数字越小越紧急。 */
    public int rank() {
        return rank;
    }

    /** a 是否比 b 更紧急（a.rank &lt;= b.rank 时 true，即 a 达到 b 的阈值）。 */
    public static boolean meets(String a, String b) {
        try {
            return valueOf(a).rank <= valueOf(b).rank;
        } catch (Exception e) {
            return false;
        }
    }
}
