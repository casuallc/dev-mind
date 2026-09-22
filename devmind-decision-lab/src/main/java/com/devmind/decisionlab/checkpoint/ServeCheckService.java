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
import java.util.Locale;
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
 * <p><b>"实际来源 vs 登记来源"这一条现在是真检查了</b>（FR-01 的 {@code /healthz.sources} 落地后，
 * {@code LayaDecisionClient.Health} 带上了 {@code sources}）：边车自己说得出某个槽位加载的是哪个
 * 目录/仓库，就能拿它与 {@code decision_checkpoints.source_path} 逐字对。对上才是"登记的这份在服务"，
 * 对不上就是本类最想拦的那件事。边车是 FR-01 之前的老版本、报不出 sources 时，这一条是
 * <b>WARN 而不是 OK</b>——缺的检查不许显示成一个恒绿的"来源一致"（没验过 ≠ 验过了）。</p>
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
        // 边车自报的槽位来源原样入报告（排错要看的原值：它到底从哪个目录加载的）
        report.put("sources", health.sources());
        checks.add(new Check("边车可达", ServeCheckResult.OK, health.summary()));
        checks.add(new Check("边车状态", health.ok() ? ServeCheckResult.OK : ServeCheckResult.FAIL,
                "status=" + (health.status() == null ? "" : health.status())));

        checks.add(slotCheck(e, health));
        checks.add(sourceCheck(e, health));
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

    /**
     * 来源一致检查（FR-06 的核心）：边车<b>实际</b>加载的是不是登记的那一份。
     *
     * <p>"槽位常驻"那条只能证明边车加载了<b>某个</b>叫这个名字的槽位；槽位背后换成了别的产物
     * （节点上有人手工改了 {@code models.json}、上一份微调产物没删干净）它看不出来。这条才对得上
     * 那个唯一的凭据：边车自报的来源 vs 库里登记的 {@code source_path}。</p>
     *
     * <p>比对是<b>归一后比</b>（斜杠方向、大小写、首尾空白、尾斜杠都不算差异）：Windows 路径大小写
     * 不敏感、手填时正反斜杠混用是常态——为这些判 FAIL 只会把真问题淹没在噪音里。</p>
     */
    static Check sourceCheck(DecisionCheckpointEntity e, LayaDecisionClient.Health health) {
        String slot = e.getServeSlot();
        if (slot == null || slot.isBlank()) {
            return new Check("来源一致", ServeCheckResult.WARN, "这份产物没登记服务槽位，无法核对边车加载的来源");
        }
        Map<String, LayaDecisionClient.SlotSource> sources =
                health.sources() == null ? Map.of() : health.sources();
        LayaDecisionClient.SlotSource src = sources.get(slot);
        if (src == null) {
            return new Check("来源一致", ServeCheckResult.WARN, sources.isEmpty()
                    ? "边车未上报槽位来源（CAP-56 FR-01 之前的老版本边车）：无法核对它加载的是不是登记的这份产物"
                    : "边车未上报槽位 " + slot + " 的来源：无法核对");
        }
        if (src.local() && Boolean.FALSE.equals(src.ready())) {
            List<String> missing = src.missing() == null ? List.of() : src.missing();
            return new Check("来源一致", ServeCheckResult.FAIL, "边车该槽位的本地目录不完整"
                    + (missing.isEmpty() ? "" : "，缺 " + String.join("、", missing))
                    + "——这份权重压根加载不出来，服务的只可能是别的");
        }
        String registered = e.getSourcePath();
        if (registered == null || registered.isBlank()) {
            return new Check("来源一致", ServeCheckResult.WARN,
                    "这份产物没登记来源，无从核对（边车实际加载 " + src.source() + "）");
        }
        if (!matches(registered, src)) {
            return new Check("来源一致", ServeCheckResult.FAIL, "边车该槽位加载的是「" + src.source()
                    + "」，登记的是「" + registered + "」——闸门放行的不是正在服务的那份");
        }
        String how = Boolean.TRUE.equals(src.overridden())
                ? "（已被覆盖，非边车内置默认）" : "";
        return new Check("来源一致", ServeCheckResult.OK, "边车该槽位加载的来源与登记一致：" + src.source() + how);
    }

    /** 登记来源 vs 边车实报来源。仓库一级的登记（{@code org/model}）对上它的子目录也算一致 */
    private static boolean matches(String registered, LayaDecisionClient.SlotSource src) {
        String want = norm(registered);
        if (want.isEmpty()) {
            return false;
        }
        String repo = norm(src.repo());
        return want.equals(norm(src.source()))
                || want.equals(norm(src.path()))
                || (!repo.isEmpty() && want.equals(repo));
    }

    /** 比较用归一：斜杠方向 / 大小写（Windows 路径不敏感）/ 首尾空白 / 尾斜杠 */
    private static String norm(String s) {
        return s == null ? "" : s.trim().replace('\\', '/').replaceAll("/+$", "").toLowerCase(Locale.ROOT);
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
