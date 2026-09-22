package com.devmind.common.decision;

import java.util.Optional;

/**
 * CAP-56 FR-07 准入闸门：在"决策能力能不能用"这件事上，给判定方一个可插拔的否决位。
 *
 * <p><b>为什么需要这道闸门</b>：CAP-55 上线后真机实测发现，一个<b>能被调通的</b>决策模型
 * 不等于一个<b>可用的</b>决策模型——{@code multilingual} 在分诊题面上对三组对照（含空召回、
 * 不相关）全判「重复」，置信度 0.93~0.99。这比"边车连不上"危险得多：连不上会降级、会留痕，
 * 而恒答同一句的模型会把自信的错误写进 {@code decision_records}，那份记录又是微调的 gold
 * 来源——错的判断会自我复制。所以"路由通"之后还需要一道人确认过的准入，
 * 这道闸门就是那个位置。</p>
 *
 * <p><b>它与 {@link DecisionEngine#unavailableReason()} 的关系</b>：实现方在
 * {@code unavailableReason()} 里<b>先于</b>端点判定查闸门，于是"置灰按钮"（FR-07 的 UI）
 * 与"运行时降级"（FR-06 的降级链）共用同一个上游——两处各判一套的话，
 * 界面上按钮亮着、后端却每次降级，反过来也一样。</p>
 *
 * <p><b>模块不在场 = 没有闸门 = 保持 CAP-55 行为</b>：实现方（{@code devmind-decision-lab}）
 * 以 {@code ObjectProvider} 探测注入；没装决策实验室的部署照旧可用（那时也没有"验证过"这个概念）。
 * 返回值已脱敏，可直接展示给用户。</p>
 */
public interface DecisionGate {

    /**
     * @return 空 = 放行；有值 = 不可用原因（要能指导用户下一步做什么，例如"去决策实验室登记并验证
     *         一个 checkpoint"）
     */
    Optional<String> unavailableReason();
}
