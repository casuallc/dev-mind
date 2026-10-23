package com.devmind.knowledge.governance;

import java.time.Instant;
import java.util.List;

/**
 * CAP-85 FR-30 检索评测报告。指标只对可回答题计算；拒答率只对不可回答题计算。
 *
 * @param recallAtK         可回答题中前 K 出现期望文档/片段的比例
 * @param mrr               首个相关结果排名倒数的均值
 * @param citationPrecision 前 K 结果中命中期望文档/片段的比例（生成引用的上界代理）
 * @param refusalRate       不可回答题中检索为空的比例
 * @param degradedReason    NONE | 数据集中缺失/非法 | 检索降级原因汇总
 */
public record EvalReport(String dataset, String datasetPath, Instant generatedAt, int topK, Long kbId,
                         int caseCount, int answerableCount, int unanswerableCount,
                         double recallAtK, double mrr, double citationPrecision, double refusalRate,
                         String degradedReason, List<CaseResult> cases) {

    public record CaseResult(String id, String question, boolean answerable, boolean hit,
                             int firstRelevantRank, double reciprocalRank, double citationPrecision,
                             boolean refused, List<HitRef> hits,
                             List<String> expectedEntryNames, List<String> expectedChunkContains) {
    }

    public record HitRef(int rank, Long entryId, String title, String docType, boolean relevant) {
    }
}
