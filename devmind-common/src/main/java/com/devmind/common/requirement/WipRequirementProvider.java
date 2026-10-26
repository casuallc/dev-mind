package com.devmind.common.requirement;

import java.util.List;

/**
 * CAP-88 FR-10 在办需求 SPI：给「AI 建议计划」等组装层场景提供某人当前在办的需求清单。
 *
 * <p>实现方 {@code devmind-project}（CAP-13 研发主线 Requirement）；消费方
 * （devmind-worklog 等）以 {@code ObjectProvider<WipRequirementProvider>} <b>探测注入</b>：
 * 未装配 = 没有研发主线模块 = 建议素材退化为「只有上周未完成项」，不阻断生成。</p>
 *
 * <h2>契约</h2>
 * <ul>
 *   <li><b>只读</b>：实现方不得有副作用；调用方拿到的只是素材快照，不回写需求侧。</li>
 *   <li><b>缺席降级</b>：实现缺席或查询异常时消费方按「无在办需求素材」继续，
 *       不得把异常透传给用户请求。</li>
 *   <li><b>limit 由调用方定</b>（素材进 prompt，宜 20 以内），实现方按更新时间新近优先截断。</li>
 * </ul>
 */
public interface WipRequirementProvider {

    /**
     * 某用户当前在办（进行中/待验收口径由实现方定）的需求，新近更新优先。
     *
     * @param userId 平台用户名（owner 口径）
     * @param limit  返回上限
     * @return 在办需求清单；无在办或查询失败返回空表（实现方自行吞异常并记日志）
     */
    List<WipRequirement> findWip(String userId, int limit);

    /**
     * 一条在办需求的素材快照。
     *
     * @param requirementId 需求 id（平台内部 id 或外部键，展示用）
     * @param title         标题
     * @param status        状态码（如 IN_PROGRESS/ACCEPTANCE，展示用原值）
     * @param projectName   所属项目名（可空——项目被删等边界时不阻断）
     */
    record WipRequirement(String requirementId, String title, String status, String projectName) {
    }
}
