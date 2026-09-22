package com.devmind.decisionlab.dataset.dto;

import java.util.List;
import java.util.Map;

/**
 * CAP-56 收编预览：动手之前先说清"能收多少、收不了的为什么"。
 *
 * <p>这是收编这个动作里最要紧的一半。回流集的价值在于"样本来自真实使用"，而真实使用里
 * 绝大多数记录是<b>收不了的</b>（只分诊过没裁决、裁决了但动作不构成答案）——如果收编只回一句
 * "已收编 3 条"，人就无从知道是数据太干净还是筛错了时间。</p>
 *
 * @param capability  回显筛选条件（null = 全部能力）
 * @param since       回显筛选条件（null = 不限时间）
 * @param total       命中的记录总数（可能大于 {@code scanned}）
 * @param scanned     实际判过的条数（上限 2000）
 * @param truncated   {@code total > scanned}：命中的记录没判完，要按能力/时间收窄再收（<b>不静默截断</b>）
 * @param collectable 可收编条数
 * @param skipReasons 原因码 → 条数（四个原因都出现，缺的记 0）
 * @param caseGroups  可收编的样本会进哪些组（含按内容自动识别的对照组）
 * @param samples     逐条判定样例（前若干条，用于核对筛出来的到底是不是想要的）
 */
public record RecordsPreview(
        String capability,
        String since,
        long total,
        int scanned,
        boolean truncated,
        int collectable,
        Map<String, Long> skipReasons,
        Map<String, Long> caseGroups,
        List<Candidate> samples) {

    /**
     * 一条记录的判定结果。
     *
     * @param outcome    {@code COLLECT} / {@code SKIP}
     * @param reasonCode 拒收原因码（可收编时为 null）
     * @param reasonLabel 原因人话（可收编时为 null）
     * @param detail     补充说明（题面差在哪一题之类）
     */
    public record Candidate(
            long recordId,
            String capability,
            String subjectId,
            String title,
            String humanAction,
            String caseGroup,
            String caseGroupLabel,
            String outcome,
            String reasonCode,
            String reasonLabel,
            String detail) {
    }
}
