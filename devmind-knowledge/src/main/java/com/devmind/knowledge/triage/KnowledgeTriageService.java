package com.devmind.knowledge.triage;

import com.devmind.common.decision.DecisionEngine;
import com.devmind.common.decision.DecisionRecordSink;
import com.devmind.common.decision.DecisionResult;
import com.devmind.common.decision.TriageQuestions;
import com.devmind.common.knowledge.KnowledgeRetriever;
import com.devmind.common.model.LayaDecisionClient;
import com.devmind.knowledge.dto.TriageStatusView;
import com.devmind.knowledge.model.KnowledgeBaseEntity;
import com.devmind.knowledge.model.KnowledgeProposalEntity;
import com.devmind.knowledge.repo.KnowledgeBaseRepository;
import com.devmind.knowledge.repo.KnowledgeProposalRepository;
import com.devmind.project.ProjectService;
import com.devmind.project.model.Project;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * CAP-55 FR-04 提案分诊：给一条待审提案出「采纳层级 / 是否重复 / 质量分」三条建议，
 * 结果落 {@code knowledge_proposals.triage_json}，供 inbox 出徽标。
 *
 * <p><b>本类不带事务</b>（同 {@code KnowledgeIndexService}）：决策调用是网络 IO，
 * 落库交给 {@link KnowledgeTriageWriter} 的短事务。</p>
 *
 * <p><b>永不上抛</b>：{@link DecisionEngine#decide} 的契约是不抛；此外召回、样本落库、
 * 结果落库都各自兜住自己那层异常——分诊是锦上添花的能力，边车挂了不能让提案入库跟着失败
 * （FR-06 降级链的落点就在这）。降级也照写一行 {@code triage_degraded=true}：
 * "为什么没徽标"要能在数据里回答，而不是只留在当天的日志里。</p>
 *
 * <p><b>不自动重试存量</b>：启动时不做 sweep（与索引清扫不同）——一次决策是 200-500ms 的
 * CPU 前向，攒了几百条降级行会变成开机后持续几分钟的边车压力；重新分诊由人手点按钮触发，
 * 是明确意图，不是猜测。</p>
 */
@Service
public class KnowledgeTriageService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeTriageService.class);

    /** decision_records 的能力标识（导出与筛选按它走，改名等于换数据集） */
    public static final String CAPABILITY = "kb-proposal-triage";

    /** 决策能力整块没装配（devmind-decision 不在场） */
    static final String NO_ENGINE = "决策引擎未装配：请确认已启用决策能力（CAP-55）";

    /** 提案标题进 state / 召回 query 的上限（标题本该短，超了说明提案本身要整理） */
    private static final int TITLE_MAX_CHARS = 200;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final KnowledgeProposalRepository proposalRepo;
    private final KnowledgeBaseRepository kbRepo;
    private final ProjectService projectService;
    private final ObjectProvider<KnowledgeRetriever> retrieverProvider;
    private final ObjectProvider<DecisionEngine> engineProvider;
    private final ObjectProvider<DecisionRecordSink> sinkProvider;
    private final KnowledgeTriageWriter writer;

    public KnowledgeTriageService(KnowledgeProposalRepository proposalRepo,
                                  KnowledgeBaseRepository kbRepo,
                                  ProjectService projectService,
                                  ObjectProvider<KnowledgeRetriever> retrieverProvider,
                                  ObjectProvider<DecisionEngine> engineProvider,
                                  ObjectProvider<DecisionRecordSink> sinkProvider,
                                  KnowledgeTriageWriter writer) {
        this.proposalRepo = proposalRepo;
        this.kbRepo = kbRepo;
        this.projectService = projectService;
        this.retrieverProvider = retrieverProvider;
        this.engineProvider = engineProvider;
        this.sinkProvider = sinkProvider;
        this.writer = writer;
    }

    /**
     * 分诊一条提案并落库。返回值只给调用方（异步线程/单测）判断用，业务不看它。
     *
     * @return 模型结果；降级时 {@link DecisionResult#degraded()} 为 true（已落库，UI 不出徽标）
     */
    public DecisionResult triage(long proposalId) {
        KnowledgeProposalEntity proposal = proposalRepo.findById(proposalId).orElse(null);
        if (proposal == null) {
            return DecisionResult.degraded("提案不存在: " + proposalId, 0);
        }
        DecisionEngine engine = engineProvider.getIfAvailable();
        TriageEvidence evidence = engine == null
                ? TriageEvidence.unavailable(NO_ENGINE)
                : collectEvidence(proposal);
        Map<String, Object> state = buildState(proposal, evidence);
        Map<String, Map<String, Object>> questions = TriageQuestions.standard();

        DecisionResult result;
        if (engine == null) {
            result = DecisionResult.degraded(NO_ENGINE, 0);
        } else {
            // 契约：decide 永不上抛（降级也是 DecisionResult 的一种）
            result = engine.decide(state, questions);
            recordSuggestion(proposalId, state, questions, result);
        }

        TriageSnapshot snapshot = new TriageSnapshot(
                TriageSnapshot.VERSION, result.degraded(), result.degradedReason(),
                result.routingModel(), result.routingReason(), result.latencyMs(),
                evidence.toSnapshot(), result.answers());
        return persist(proposalId, snapshot, result);
    }

    /** FR-07 置灰用：配置侧能不能分诊（只读配置，不探活——边车关停要点了才知道） */
    public TriageStatusView status() {
        DecisionEngine engine = engineProvider.getIfAvailable();
        if (engine == null) {
            return new TriageStatusView(false, NO_ENGINE);
        }
        try {
            return engine.unavailableReason()
                    .map(reason -> new TriageStatusView(false, reason))
                    .orElseGet(() -> new TriageStatusView(true, ""));
        } catch (Exception e) {
            // 状态端点绝不能 500：UI 拿它决定按钮灰不灰，崩了就变成红报错
            log.warn("决策可用性判断异常: {}", e.toString());
            return new TriageStatusView(false, "决策引擎不可用：" + e.getMessage());
        }
    }

    // ---------------- state / 证据 ----------------

    /**
     * 组装 state。<b>刻意不放"提案人自称的去向"</b>：模型的价值就在于不同意提案人，
     * 把自称值喂进去等于给它一个锚。项目名要放——"只对本项目成立"没有项目是什么根本判不了。
     */
    private Map<String, Object> buildState(KnowledgeProposalEntity p, TriageEvidence evidence) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("proposal_title", cap(p.getTitle(), TITLE_MAX_CHARS));
        state.put("proposal_content", cap(p.getContentMd(), LayaDecisionClient.STATE_VALUE_MAX_CHARS));
        state.put("project", projectLabel(p.getTargetProjectId()));
        state.put("similar_entries", evidence.stateText());
        return state;
    }

    private String projectLabel(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return "（未指定项目）";
        }
        try {
            Project project = projectService.requireProject(projectId);
            return project.name() + "（" + projectId + "）";
        } catch (Exception e) {
            // 项目被删了也要能分诊：退化成裸 id
            return projectId;
        }
    }

    /** 全库召回：跨项目重复同样值得提示（"这条别的项目已经踩过"正是要沉淀的东西） */
    private TriageEvidence collectEvidence(KnowledgeProposalEntity proposal) {
        KnowledgeRetriever retriever = retrieverProvider.getIfAvailable();
        if (retriever == null) {
            return TriageEvidence.unavailable("检索能力未装配（CAP-44），重复判定无依据");
        }
        List<Long> kbIds = kbRepo.findAll().stream().map(KnowledgeBaseEntity::getId).toList();
        if (kbIds.isEmpty()) {
            return TriageEvidence.unavailable("库内暂无可比对条目");
        }
        // query 用标题、不用全文：LIKE 兜底是整串匹配，喂几百字进去必然 0 命中——
        // 那样"没配 embedding"这条路径就永远召不回任何东西（重复判定形同虚设）；
        // 标题是主题摘要，向量通道与关键词通道都吃它，全文本来就已进 state 给模型看
        String query = cap(proposal.getTitle(), TITLE_MAX_CHARS);
        try {
            return TriageEvidence.of(retriever.retrieveDetailed(kbIds, query, TriageQuestions.SIMILAR_TOP_K));
        } catch (Exception e) {
            // SPI 契约说"检索失败按无命中降级"，但那是实现方的自觉；调用方自己再兜一层更省心
            log.warn("分诊召回失败（按无依据降级）: proposal={} err={}", proposal.getId(), e.toString());
            return TriageEvidence.unavailable("检索失败，重复判定无依据");
        }
    }

    // ---------------- 落库 ----------------

    /**
     * 记训练样本（FR-05）。降级样本也记——"模型没给出建议"恰恰是评估可用性的第一手数据。
     * sink 契约要求"失败不抛"，这里仍兜一层：样本落库绝不该影响分诊本身。
     */
    private void recordSuggestion(long proposalId, Map<String, Object> state,
                                  Map<String, Map<String, Object>> questions, DecisionResult result) {
        DecisionRecordSink sink = sinkProvider.getIfAvailable();
        if (sink == null) {
            return;
        }
        try {
            sink.saveSuggestion(CAPABILITY, String.valueOf(proposalId), state, questions, result);
        } catch (Exception e) {
            log.warn("决策样本落库失败（不影响分诊）: proposal={} err={}", proposalId, e.toString());
        }
    }

    private DecisionResult persist(long proposalId, TriageSnapshot snapshot, DecisionResult result) {
        String json;
        try {
            json = MAPPER.writeValueAsString(snapshot);
        } catch (Exception e) {
            // 结构是我们自己的 record，正常不该失败；真失败了就当这次没分诊（triage_at 留空 = 可重试）
            log.warn("分诊快照序列化失败: proposal={} err={}", proposalId, e.toString());
            return DecisionResult.degraded("分诊结果序列化失败", result.latencyMs());
        }
        try {
            writer.write(proposalId, json, result.degraded());
        } catch (Exception e) {
            log.warn("分诊结果落库失败: proposal={} err={}", proposalId, e.toString());
            return DecisionResult.degraded("分诊结果落库失败：" + e.getMessage(), result.latencyMs());
        }
        return result;
    }

    /**
     * 自己先截到位，让<b>落进训练集的那份 state 与模型看到的逐字一致</b>
     * （引擎里的 1500 字截断因此只剩兜底作用——不一致的样本是数据飞轮里最难发现的脏数据）。
     */
    private static String cap(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxChars ? value : value.substring(0, maxChars) + "…（已截断）";
    }
}
