package com.devmind.decision;

import com.devmind.common.decision.DecisionEngine;
import com.devmind.common.decision.DecisionResult;
import com.devmind.common.model.LayaDecisionClient;
import com.devmind.common.model.LayaDecisionException;
import com.devmind.common.model.ModelCallException;
import com.devmind.common.model.ModelEndpointProvider;
import com.devmind.common.model.ModelEndpointView;
import com.devmind.decision.config.DecisionProperties;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * CAP-55 FR-03 决策引擎的 HTTP 实现：把 {@link DecisionEngine#decide} 落到 CAP-48 的
 * 平台默认 {@code kind=DECISION} 端点上（laya 边车协议，见 {@link LayaDecisionClient}）。
 *
 * <p><b>降级链（FR-06）</b>：模块没装配 / 没配 DECISION 端点 / 端点不完整 / 边车连不上超时 /
 * 应答不可解析 → 一律 {@link DecisionResult#degraded}（<b>永不上抛</b>）。决策是锦上添花的能力，
 * 边车挂了不能让知识库的写入变成 500。</p>
 *
 * <p><b>重试口径</b>：只对"看着像抖"的失败重试一次（连不上/读超时/5xx），
 * 4xx 与解析失败不重试（结果不会变，只把延迟翻倍）——判定在
 * {@link LayaDecisionException#retryable()}，代价是这里要多 catch 一层。</p>
 *
 * <p><b>不做健康检查前置</b>：每次分诊先打一次 {@code /healthz} 会把往返数翻倍而收益有限
 * （healthz 过了也不代表这次前向能成）。连接测试探针是另一回事——那边由人盯着，
 * 两段实调才有诊断价值。</p>
 */
@Component
public class HttpDecisionEngine implements DecisionEngine {

    private static final Logger log = LoggerFactory.getLogger(HttpDecisionEngine.class);

    private static final String NO_PROVIDER =
            "决策引擎未装配：请确认已启用决策能力（CAP-55）";
    private static final String NO_ENDPOINT =
            "未配置平台默认决策端点：请在「后台 → 模型接入」登记 kind=决策 的端点并设为默认";

    private final ObjectProvider<ModelEndpointProvider> endpointProviders;
    private final DecisionProperties props;

    public HttpDecisionEngine(ObjectProvider<ModelEndpointProvider> endpointProviders,
                              DecisionProperties props) {
        this.endpointProviders = endpointProviders;
        this.props = props;
    }

    @Override
    public DecisionResult decide(Map<String, Object> state,
                                 Map<String, Map<String, Object>> questions) {
        long t0 = System.nanoTime();
        // 端点解析与入参无关，先做：没端点就直接降级，一次网络都不发
        Optional<String> blocked = unavailableReason();
        if (blocked.isPresent()) {
            return DecisionResult.degraded(blocked.get(), msSince(t0));
        }
        ModelEndpointView ep = resolve().orElseThrow();
        LayaDecisionClient.Options opt = new LayaDecisionClient.Options(
                ep.baseUrl(), ep.apiKey(), ep.model(), props.getTimeoutSeconds());

        int attempts = Math.max(1, props.getRetryCount() + 1);
        ModelCallException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                LayaDecisionClient.Reply reply = LayaDecisionClient.predict(opt, state, questions);
                return DecisionResult.ok(reply.answers(), reply.routingModel(),
                        reply.routingReason(), msSince(t0));
            } catch (ModelCallException e) {
                last = e;
                boolean retryable = e instanceof LayaDecisionException lde && lde.retryable();
                if (!retryable || attempt == attempts) {
                    break;
                }
                log.debug("决策调用失败将重试（第 {} 次）：{}", attempt, e.getMessage());
                sleep(props.getRetryBackoffMillis());
            }
        }
        // 消息已脱敏（客户端 send() 里过的 sanitize），可直接回显与落库
        return DecisionResult.degraded(last == null ? "决策调用失败" : last.getMessage(), msSince(t0));
    }

    @Override
    public Optional<String> unavailableReason() {
        ModelEndpointProvider provider = endpointProviders.getIfAvailable();
        if (provider == null) {
            return Optional.of(NO_PROVIDER);
        }
        Optional<ModelEndpointView> found =
                provider.defaultEndpoint(ModelEndpointView.KIND_DECISION)
                        .filter(ModelEndpointView::decision);
        if (found.isEmpty()) {
            return Optional.of(NO_ENDPOINT);
        }
        ModelEndpointView ep = found.get();
        if (isBlank(ep.baseUrl())) {
            // mock 端点（CAP-48 的测试provider）没有地址，只能用于连接测试自报——真实决策需要真的边车
            return Optional.of("决策端点「" + ep.display() + "」未配置 baseUrl（"
                    + (ep.mock() ? "mock 端点只能用于连接测试，不能用于真实决策" : "请补填边车根地址")
                    + "）");
        }
        return Optional.empty();
    }

    private Optional<ModelEndpointView> resolve() {
        ModelEndpointProvider provider = endpointProviders.getIfAvailable();
        if (provider == null) {
            return Optional.empty();
        }
        return provider.defaultEndpoint(ModelEndpointView.KIND_DECISION)
                .filter(ModelEndpointView::decision)
                .filter(ep -> !isBlank(ep.baseUrl()));
    }

    /** 重试前的小睡；被中断就恢复中断位并放弃剩余重试（有人在喊停，不该顶着停机信号再打） */
    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long msSince(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
