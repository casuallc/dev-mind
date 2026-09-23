package com.devmind.classify.playground;

import com.devmind.classify.config.ClassifyProperties;
import com.devmind.classify.instance.model.ClassifyInstanceEntity;
import com.devmind.classify.instance.repo.ClassifyInstanceRepository;
import com.devmind.classify.playground.dto.PlaygroundRunRequest;
import com.devmind.classify.playground.dto.PlaygroundRunView;
import com.devmind.common.decision.DecisionEngine;
import com.devmind.common.decision.DecisionRecordSink;
import com.devmind.common.decision.DecisionResult;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.common.model.LayaDecisionClient;
import com.devmind.common.model.ModelEndpointProvider;
import com.devmind.common.model.ModelEndpointView;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * CAP-57 FR-04 在线试分类：页面级诊断工具，三通道解析目标后跑一次 {@code /v1/predict}。
 *
 * <p><b>三通道</b>（互斥，按 instanceId → endpointId → 缺省的优先级解释）：</p>
 * <ul>
 *   <li>instanceId：直打受管实例 baseUrl（{@link LayaDecisionClient} 本身就是薄封装，
 *       异常<b>可上抛</b>——诊断场景就是要看到「连不上/答非所问」的原文，不适用
 *       业务流程「永不上抛」的降级契约）；</li>
 *   <li>endpointId：经 {@link ModelEndpointProvider} 解析指定 DECISION 端点（带 apiKey/model）；</li>
 *   <li>缺省：{@link DecisionEngine} 平台默认决策链（含降级语义原样透传）。</li>
 * </ul>
 *
 * <p>每次运行经 {@link ObjectProvider}<code>&lt;DecisionRecordSink&gt;</code> 落
 * capability={@value #CAPABILITY}、refId=pg-&lt;uuid&gt; 的建议记录——playground 也是
 * 「模型答了一次」，进决策记录才能与正式流量用同一套依据审查；sink 未装配不炸（仅跳过记录）。</p>
 */
@Service
public class ClassifyPlaygroundService {

    private static final Logger log = LoggerFactory.getLogger(ClassifyPlaygroundService.class);

    /** 决策记录 capability（验收口径：decision_records 出现该 capability 的行） */
    public static final String CAPABILITY = "classify-playground";

    private final ClassifyInstanceRepository instanceRepo;
    private final ClassifyProperties props;
    private final ObjectProvider<ModelEndpointProvider> endpointProvider;
    private final ObjectProvider<DecisionEngine> engineProvider;
    private final ObjectProvider<DecisionRecordSink> sinkProvider;

    public ClassifyPlaygroundService(ClassifyInstanceRepository instanceRepo,
                                     ClassifyProperties props,
                                     ObjectProvider<ModelEndpointProvider> endpointProvider,
                                     ObjectProvider<DecisionEngine> engineProvider,
                                     ObjectProvider<DecisionRecordSink> sinkProvider) {
        this.instanceRepo = instanceRepo;
        this.props = props;
        this.endpointProvider = endpointProvider;
        this.engineProvider = engineProvider;
        this.sinkProvider = sinkProvider;
    }

    public PlaygroundRunView run(PlaygroundRunRequest req) {
        if (req == null || req.questions() == null || req.questions().isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "questions 必填且至少一题");
        }
        if (req.instanceId() != null && req.endpointId() != null) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "instanceId 与 endpointId 互斥（留空走平台默认决策链）");
        }
        Map<String, Object> state = req.state() == null ? Map.of() : req.state();
        long start = System.currentTimeMillis();
        DecisionResult result;
        String target;
        if (req.instanceId() != null) {
            ClassifyInstanceEntity inst = instanceRepo.findById(req.instanceId()).orElseThrow(
                    () -> new DevMindException(ErrorCode.NOT_FOUND,
                            "分类实例不存在: id=" + req.instanceId()));
            target = "实例「" + inst.getName() + "」" + inst.getBaseUrl();
            result = predictDirect(new LayaDecisionClient.Options(inst.getBaseUrl(), null, null,
                    props.getPlaygroundTimeoutSeconds()), state, req.questions(), start);
        } else if (req.endpointId() != null) {
            ModelEndpointProvider provider = endpointProvider.getIfAvailable();
            if (provider == null) {
                throw new DevMindException(ErrorCode.CONFLICT, "model 模块未装配，无法解析端点");
            }
            ModelEndpointView ep = provider.activeEndpoint(req.endpointId())
                    .filter(ModelEndpointView::decision)
                    .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND,
                            "DECISION 端点不存在或已停用: id=" + req.endpointId()));
            target = "端点「" + ep.display() + "」" + ep.baseUrl();
            result = predictDirect(new LayaDecisionClient.Options(ep.baseUrl(), ep.apiKey(),
                    ep.model(), props.getPlaygroundTimeoutSeconds()), state, req.questions(), start);
        } else {
            DecisionEngine engine = engineProvider.getIfAvailable();
            if (engine == null) {
                throw new DevMindException(ErrorCode.CONFLICT,
                        "决策引擎未装配（devmind-decision）：请改用 instanceId 直打受管实例");
            }
            target = "平台默认决策链";
            result = engine.decide(state, req.questions());
        }

        String refId = "pg-" + UUID.randomUUID().toString().substring(0, 8);
        DecisionRecordSink sink = sinkProvider.getIfAvailable();
        if (sink != null) {
            try {
                sink.saveSuggestion(CAPABILITY, refId, state, req.questions(), result);
            } catch (Exception e) {
                // 记录失败不炸诊断主流程（同业务流程口径：sink 是旁路）
                log.warn("试分类决策记录写入失败（结果仍返回）: {}", e.getMessage());
            }
        }
        log.info("试分类: target={} ref={} answers={} degraded={}", target, refId,
                result.answers().size(), result.degraded());
        return new PlaygroundRunView(refId, target, result.answers(), result.routingModel(),
                result.routingReason(), result.degraded(), result.degradedReason(), result.latencyMs());
    }

    /** 直打通道：client 异常原样上抛（诊断要看到原文），结果包装成 DecisionResult 统一记录 */
    private DecisionResult predictDirect(LayaDecisionClient.Options opt, Map<String, Object> state,
                                         Map<String, Map<String, Object>> questions, long start) {
        LayaDecisionClient.Reply reply = LayaDecisionClient.predict(opt, state, questions);
        return DecisionResult.ok(reply.answers(), reply.routingModel(), reply.routingReason(),
                System.currentTimeMillis() - start);
    }

    /** 预填样例（与边车连接测试同一份中文样例——验的正是 multilingual 槽位读不读得懂中文） */
    public Map<String, Object> sample() {
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("state", LayaDecisionClient.sampleState());
        sample.put("questions", LayaDecisionClient.sampleQuestions());
        return sample;
    }
}
