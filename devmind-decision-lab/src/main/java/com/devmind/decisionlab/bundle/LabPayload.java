package com.devmind.decisionlab.bundle;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.decision.record.GoldDistributions;
import com.devmind.decisionlab.dataset.model.DecisionDatasetItemEntity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * CAP-56 执行包内 {@code payload.json} 的装配：把库里的任务行与评测样本摊成脚本能直接吃的 JSON。
 *
 * <p><b>为什么样本里同时给 {@code gold} 和 {@code goldDistribution}</b>：脚本要评的是"模型输出
 * 与标准答案的差距"，而"标准答案"在平台侧有一个明确定义——{@link GoldDistributions} 把人工原值
 * 按题面 criteria 摊成分布（choice 单选、score 等级下标、noul 是/否）。
 * 让 python 再实现一遍这个换算，等于把同一口径写两处：训练用的分布与评测用的分布哪天出现
 * 一分一毫的差别，报出来的指标就不再是"这个模型在训练目标上的表现"，而没人会发现。
 * 所以<b>换算只在这里做一次</b>，脚本直接用 {@code goldDistribution} 打分；
 * {@code gold} 原值一并带上，是为了报告里能让人看到"这道题人工答的是什么"（追溯），而不是为了再算一遍。</p>
 *
 * <p><b>解析失败上抛</b>（与读路径的 {@code DatasetJson} 相反）：那些 JSON 是我们自己
 * {@code write} 进去的，读不出来就是数据坏了。坏数据在打包时丢掉一条，会让报告里的
 * 准确率分母悄悄变小——宁可不跑，也不要一份说得通的错数字。</p>
 */
@Component
public class LabPayload {

    /** payload 结构版本（脚本按它决定怎么解析；改结构必须+1，否则老节点会静默理解错） */
    public static final int SCHEMA_VERSION = 1;

    private static final TypeReference<Map<String, Object>> GENERIC = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Map<String, Object>>> QUESTIONS = new TypeReference<>() {
    };

    private final ObjectMapper mapper;

    public LabPayload(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 一条样本的载荷。
     *
     * @param node           脚本读的那份 JSON
     * @param questionCount  题面有几题
     * @param scorableCount  其中 gold 落得上题面的题数（&lt; 题数 = 标注不全，指标只覆盖这几题）
     */
    public record Item(Map<String, Object> node, int questionCount, int scorableCount) {
    }

    public Item item(DecisionDatasetItemEntity row) {
        Map<String, Object> state = read(row.getStateJson(), GENERIC, row.getId(), "state");
        Map<String, Map<String, Object>> questions = read(row.getQuestionsJson(), QUESTIONS, row.getId(), "questions");
        Map<String, Object> gold = read(row.getGoldJson(), GENERIC, row.getId(), "gold");
        if (questions.isEmpty()) {
            throw corrupt(row.getId(), "questions", "题面为空");
        }
        Map<String, Object> distributions = GoldDistributions.of(questions, gold);

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", row.getId());
        node.put("caseGroup", row.getCaseGroup());
        node.put("source", row.getSource());
        node.put("originRecordId", row.getOriginRecordId());
        node.put("note", row.getNote());
        node.put("questionSetVersion", row.getQuestionSetVersion());
        node.put("state", state);
        node.put("questions", questions);
        node.put("gold", gold);
        node.put("goldDistribution", distributions);
        node.put("questionCount", questions.size());
        node.put("scorableCount", distributions.size());
        return new Item(node, questions.size(), distributions.size());
    }

    /**
     * 复制一份节点并打上切分标记（微调包专用）。
     *
     * <p>切分由服务端决定（{@code FinetuneSplit}，结果入库），脚本<b>按标记分流</b>而不是自己
     * 再切一次——脚本自己切的话，"报告里那个 val 指标在哪几条上算出来的"就没人答得上来了
     * （同一个 seed 在不同版本/不同读取顺序下会切出不同集合）。</p>
     *
     * <p>不回写原 map 而是复制：{@link Item#node()} 是评测与微调共用的构建结果，
     * 在一处顺手加上 {@code split} 字段，另一个包的语义就悄悄变了。</p>
     */
    public static Item withSplit(Item item, String split) {
        Map<String, Object> node = new LinkedHashMap<>(item.node());
        node.put("split", split);
        return new Item(node, item.questionCount(), item.scorableCount());
    }

    /** 逐题标注覆盖面的一句话（进 payload 的 warnings；脚本会把它带进报告） */
    public String coverageWarning(List<Item> items) {
        int partial = 0;
        int unscorable = 0;
        List<Long> sample = new ArrayList<>();
        for (Item item : items) {
            if (item.scorableCount() >= item.questionCount()) {
                continue;
            }
            if (item.scorableCount() == 0) {
                unscorable++;
            } else {
                partial++;
            }
            if (sample.size() < 5) {
                sample.add((Long) item.node().get("id"));
            }
        }
        if (partial == 0 && unscorable == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (partial > 0) {
            sb.append(partial).append(" 条样本的 gold 未覆盖全部题面（指标只按已标注的题算）");
        }
        if (unscorable > 0) {
            sb.append(sb.length() > 0 ? "；" : "")
                    .append(unscorable).append(" 条样本 gold 一题都落不上题面（完全进不了指标）");
        }
        sb.append("，如样本 ").append(sample.stream().map(id -> "#" + id).collect(Collectors.joining("、")));
        return sb.toString();
    }

    /** 序列化 payload（节点的 python 直接 json.load 它） */
    public byte[] bytes(Object payload) {
        try {
            return mapper.writeValueAsBytes(payload);
        } catch (Exception e) {
            throw new DevMindException(ErrorCode.INTERNAL, "执行包数据序列化失败: " + e.getMessage());
        }
    }

    /** 空 = 没写（理论上不该出现，样本落库时 state/gold 必非空）；坏 = 上抛 */
    private <T> T read(String json, TypeReference<T> type, Long itemId, String field) {
        if (json == null || json.isBlank()) {
            throw corrupt(itemId, field, "为空");
        }
        try {
            T parsed = mapper.readValue(json, type);
            if (parsed == null) {
                throw corrupt(itemId, field, "为 null");
            }
            return parsed;
        } catch (DevMindException e) {
            throw e;
        } catch (Exception e) {
            throw corrupt(itemId, field, "不是合法 JSON");
        }
    }

    private static DevMindException corrupt(Long itemId, String field, String why) {
        return new DevMindException(ErrorCode.BAD_REQUEST,
                "评测样本 #" + itemId + " 的 " + field + " " + why
                        + "：执行包按库里的样本原样构建，读不出来就没法保证报告的分母是对的");
    }
}
