package com.devmind.decisionlab.checkpoint;

import com.devmind.common.model.LayaDecisionClient;
import com.devmind.common.model.ModelCallException;
import com.devmind.common.model.ModelEndpointProvider;
import com.devmind.common.model.ModelEndpointView;
import com.devmind.decisionlab.checkpoint.dto.ServeCheckResult;
import com.devmind.decisionlab.checkpoint.dto.ServeCheckResult.Check;
import com.devmind.decisionlab.checkpoint.model.DecisionCheckpointEntity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * CAP-56 FR-06 的 serve 自检：打一次边车 {@code /healthz}，核对"登记的那份"是不是"此刻在跑的那份"。
 *
 * <p><b>核对的是槽位常驻，不是逐字节同一</b>：healthz 说得出"我加载了哪些槽位/在什么设备上"，
 * 说不出权重文件的 sha256——那是磁盘上的事，得在节点上算（FR-06 的指纹登记在微调/评测收尾时做，
 * 见 {@code tools/laya-sidecar/lab/laya_eval.py}）。所以这一步的语义是：
 * 库里的槽位真的在边车常驻清单里吗？不在，就说明放行的这份模型根本没在服务。</p>
 *
 * <p><b>边车上报槽位来源（FR-01）落地后</b>，这里会多一条"实际来源 vs 登记来源"的比对
 * （届时 {@code LayaDecisionClient.Health} 会带上 sources 字段）。在那之前，缺的那一条检查
 * <b>不假装通过</b>——没有的检查项就是不出现，而不是显示一个恒绿的"来源一致"。</p>
 *
 * <p>超时固定 10 秒，不引配置项：这是人盯着点的诊断（不是流量路径），10 秒足够分辨
 * "边车没起"与"边车在忙"；为它再加一个要配的超时项，本身就多了一处会配错的地方。</p>
 */
@Service
public class ServeCheckService {

    private static final Logger log = LoggerFactory.getLogger(ServeCheckService.class);

    private static final int TIMEOUT_SECONDS = 10;

    private static final String NO_ENDPOINT =
            "未配置平台默认决策端点：请在「后台 → 模型接入」登记 kind=决策 的端点并设为默认";

    private final ObjectProvider<ModelEndpointProvider> endpointProviders;

    public ServeCheckService(ObjectProvider<ModelEndpointProvider> endpointProviders) {
        this.endpointProviders = endpointProviders;
    }

    public ServeCheckResult run(DecisionCheckpointEntity e) {
        List<Check> checks = new ArrayList<>();
        Map<String, Object> report = new LinkedHashMap<>();
        Instant now = Instant.now();

        Optional<ModelEndpointView> found = resolveEndpoint();
        if (found.isEmpty()) {
            checks.add(new Check("决策端点", ServeCheckResult.FAIL, NO_ENDPOINT));
            return ServeCheckResult.of(checks, report, now);
        }
        ModelEndpointView ep = found.get();
        report.put("endpoint", ep.baseUrl());
        report.put("endpointName", ep.display());
        checks.add(new Check("决策端点", ServeCheckResult.OK,
                "「" + ep.display() + "」" + ep.baseUrl()));

        LayaDecisionClient.Health health;
        try {
            health = LayaDecisionClient.healthz(new LayaDecisionClient.Options(
                    ep.baseUrl(), ep.apiKey(), ep.model(), TIMEOUT_SECONDS));
        } catch (ModelCallException ex) {
            // 消息已脱敏（客户端 send() 里过的 sanitize），可直接回显
            checks.add(new Check("边车可达", ServeCheckResult.FAIL, ex.getMessage()));
            return ServeCheckResult.of(checks, report, now);
        }
        report.put("status", health.status());
        report.put("layaVersion", health.layaVersion());
        report.put("loaded", health.loaded());
        report.put("devices", health.devices());
        checks.add(new Check("边车可达", ServeCheckResult.OK, health.summary()));
        checks.add(new Check("边车状态", health.ok() ? ServeCheckResult.OK : ServeCheckResult.FAIL,
                "status=" + (health.status() == null ? "" : health.status())));

        checks.add(slotCheck(e, health));
        checks.add(deviceCheck(e, health));
        return ServeCheckResult.of(checks, report, now);
    }

    /**
     * 槽位常驻检查：登记的槽位必须出现在边车的常驻清单里。
     *
     * <p>FAIL 而不是 WARN：边车没加载这个槽位时，它服务的仍然是别的模型（或按语言自己路由），
     * 而闸门已经按这份产物的验证放行了——评测报告与线上行为就此分家，正是这条链路最想拦住的事。</p>
     */
    private static Check slotCheck(DecisionCheckpointEntity e, LayaDecisionClient.Health health) {
        String slot = e.getServeSlot();
        List<String> loaded = health.loaded() == null ? List.of() : health.loaded();
        if (slot == null || slot.isBlank()) {
            // 登记时已强制填槽位，走到这里是存量行：说清楚而不是显示成通过
            return new Check("槽位常驻", ServeCheckResult.WARN, "这份产物没登记服务槽位，无法核对边车是否在服务它");
        }
        if (loaded.contains(slot)) {
            return new Check("槽位常驻", ServeCheckResult.OK, "边车已加载槽位 " + slot);
        }
        return new Check("槽位常驻", ServeCheckResult.FAIL,
                "边车常驻清单 " + (loaded.isEmpty() ? "为空" : String.join("、", loaded))
                        + " 不含 " + slot + "——此刻它服务的不是这份产物");
    }

    /** 设备只是信息（哪张卡在跑这份权重）；healthz 没报槽位设备时也照实说"边车没报" */
    private static Check deviceCheck(DecisionCheckpointEntity e, LayaDecisionClient.Health health) {
        Map<String, String> devices = health.devices() == null ? Map.of() : health.devices();
        String slot = e.getServeSlot();
        if (slot != null && devices.containsKey(slot)) {
            return new Check("设备", ServeCheckResult.OK, slot + " → " + devices.get(slot));
        }
        if (devices.isEmpty()) {
            return new Check("设备", ServeCheckResult.OK, "边车未上报设备占用（不影响结论）");
        }
        return new Check("设备", ServeCheckResult.OK, String.join("，",
                devices.entrySet().stream().map(d -> d.getKey() + " → " + d.getValue()).toList()));
    }

    /**
     * 取平台默认决策端点。
     *
     * <p>三个过滤条件与 {@code HttpDecisionEngine.resolve()} 逐条相同（kind=决策 / 默认 / baseUrl 非空）
     * ——自检打的是<b>决策流量走的那同一个端点</b>；两处条件一旦不同，就会出现"自检说边车好着呢、
     * 分诊却一直在降级"。</p>
     */
    private Optional<ModelEndpointView> resolveEndpoint() {
        ModelEndpointProvider provider = endpointProviders.getIfAvailable();
        if (provider == null) {
            log.debug("自检时未探测到 ModelEndpointProvider（模型模块未装配）");
            return Optional.empty();
        }
        return provider.defaultEndpoint(ModelEndpointView.KIND_DECISION)
                .filter(ModelEndpointView::decision)
                .filter(ep -> ep.baseUrl() != null && !ep.baseUrl().isBlank());
    }
}
