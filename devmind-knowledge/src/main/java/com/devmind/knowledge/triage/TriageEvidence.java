package com.devmind.knowledge.triage;

import com.devmind.common.decision.TriageQuestions;
import com.devmind.common.knowledge.KnowledgeRetriever;
import java.util.ArrayList;
import java.util.List;

/**
 * CAP-55 FR-04 重复判定的两段式：embedding 召回（便宜）→ laya noul 精判（懂语义）。
 * 本类管前一段——把召回结果既拼成喂模型的 state 片段，又凝固进落库快照。
 *
 * <p><b>为什么要存档</b>：抽屉里的「查看依据」要回答"为什么说它重复"。事后重新检索是错的：
 * 库里这段时间又进了新条目，重跑出来的相似列表和当初判断时看到的不是一份东西，
 * 徽标就变得无法解释（也复现不出训练样本的输入）。</p>
 *
 * <p><b>单条截断 300 字</b>：state 在客户端还有 1500 字/值的总闸（laya 中文预算 ~768 token），
 * 三条各截 300 才不会让第三条被整体砍掉——重复判定看的是"像不像"，开头那段足够。</p>
 */
public record TriageEvidence(String retrieval, List<KnowledgeRetriever.RetrievedChunk> chunks, String note) {

    /** 向量通道 */
    public static final String VECTOR = "vector";
    /** 无 embedding 端点，实现侧降级关键词检索（score 恒 0，别拿去当重复证据） */
    public static final String LIKE = "like";
    /** 没召回器 / 召回失败：重复判定缺判据 */
    public static final String NONE = "none";

    static final int PER_CHUNK_CHARS = 300;

    /** 检索不可用（没装配召回器，或调用抛了）——题照问，但结论要打问号 */
    public static TriageEvidence unavailable(String note) {
        return new TriageEvidence(NONE, List.of(), note);
    }

    /** 把 SPI 的 Detailed 收成证据；向量降级原因落进 note（不然后面看不懂为什么撞得不准） */
    public static TriageEvidence of(KnowledgeRetriever.Detailed detailed) {
        if (detailed == null) {
            return unavailable("检索无返回");
        }
        List<KnowledgeRetriever.RetrievedChunk> chunks = detailed.chunks() == null
                ? List.of() : detailed.chunks();
        String note = switch (detailed.degradedReason()) {
            case NO_EMBEDDING -> "检索降级：未配 embedding，走关键词匹配，重复结论仅供参考";
            case DIMENSION_MISMATCH -> "检索降级：端点维度与库内索引不一致，命中被过滤，重复结论仅供参考";
            case NONE -> "";
        };
        if (chunks.isEmpty() && note.isEmpty()) {
            note = "库内没有召回到相似条目";
        }
        return new TriageEvidence(detailed.vector() ? VECTOR : LIKE, chunks, note);
    }

    public boolean empty() {
        return chunks.isEmpty();
    }

    /**
     * 喂给模型的 state 片段（state 键 {@code similar_entries}）。
     *
     * <p>空召回那句占位文案取自 {@link TriageQuestions#EMPTY_RECALL}，不在这里另写一份：
     * CAP-56 的「空召回」对照组就是靠<b>逐字这个串</b>造的，生产侧与评测侧各写一份的话，
     * 改一处就静默失配——对照组看着还在，其实已经不是同一段输入了。</p>
     */
    public String stateText() {
        if (chunks.isEmpty()) {
            String base = TriageQuestions.EMPTY_RECALL;
            return note == null || note.isBlank() ? base : base + " " + note;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            KnowledgeRetriever.RetrievedChunk c = chunks.get(i);
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(i + 1).append(". 《").append(nullToDash(c.entryName())).append("》");
            if (VECTOR.equals(retrieval)) {
                sb.append(" 相似度 ").append(String.format("%.2f", c.score()));
            }
            sb.append('\n').append(abbreviate(c.content()));
        }
        if (note != null && !note.isBlank()) {
            sb.append("\n（").append(note).append("）");
        }
        return sb.toString();
    }

    /** 落库形状（after-the-fact 解释徽标用） */
    public TriageSnapshot.Evidence toSnapshot() {
        List<TriageSnapshot.Evidence.Similar> similar = new ArrayList<>(chunks.size());
        for (KnowledgeRetriever.RetrievedChunk c : chunks) {
            similar.add(new TriageSnapshot.Evidence.Similar(c.entryId(), c.entryName(), c.score()));
        }
        return new TriageSnapshot.Evidence(retrieval, similar, note == null ? "" : note);
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= PER_CHUNK_CHARS
                ? flat : flat.substring(0, PER_CHUNK_CHARS) + "…（已截断）";
    }

    private static String nullToDash(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }
}
