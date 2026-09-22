package com.devmind.decisionlab.checkpoint.dto;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * CAP-56 FR-06 的 serve 自检报告：登记的产物与"边车此刻真的在服务的东西"对不对得上。
 *
 * <p><b>为什么要有这一步</b>：闸门只看库里的 {@code verified}，那是"我们<b>打算</b>放行谁"；
 * 边车实际加载的是另一回事（槽位配错、模型没下下来、边车还是上个版本）。登记了 A、边车跑着 B
 * 的时候，评测报告与线上行为分属两份模型——这类错<b>只有对着边车问</b>才看得出来。</p>
 *
 * <p>检查项逐条给人看（{@link Check}），结论状态取最差项：任一 FAIL → FAIL，任一 WARN → WARN。
 * {@code report} 存原始健康应答（status / laya_version / loaded / devices），
 * 排错要看的就是这些原值。</p>
 *
 * @param status    OK / WARN / FAIL
 * @param summary   一行结论（列表页直接显示这句）
 * @param checks    逐项明细
 * @param report    边车 {@code /healthz} 的原值 + 本次核对的端点地址
 */
public record ServeCheckResult(String status, String summary, List<Check> checks,
                               Map<String, Object> report, Instant checkedAt) {

    public static final String OK = "OK";
    public static final String WARN = "WARN";
    public static final String FAIL = "FAIL";

    /** @param item 检查项名（"边车可达"） @param status OK/WARN/FAIL @param detail 人读的一句话 */
    public record Check(String item, String status, String detail) {
    }

    public static ServeCheckResult of(List<Check> checks, Map<String, Object> report, Instant checkedAt) {
        List<String> fails = named(checks, FAIL);
        List<String> warns = named(checks, WARN);
        String status = !fails.isEmpty() ? FAIL : warns.isEmpty() ? OK : WARN;
        String summary;
        if (!fails.isEmpty()) {
            summary = fails.size() + " 项未通过：" + String.join("、", fails);
        } else if (!warns.isEmpty()) {
            summary = warns.size() + " 项需确认：" + String.join("、", warns);
        } else {
            summary = "全部 " + checks.size() + " 项通过";
        }
        return new ServeCheckResult(status, summary, List.copyOf(checks), report, checkedAt);
    }

    private static List<String> named(List<Check> checks, String status) {
        List<String> names = new ArrayList<>();
        for (Check c : checks) {
            if (status.equals(c.status())) {
                names.add(c.item());
            }
        }
        return names;
    }
}
