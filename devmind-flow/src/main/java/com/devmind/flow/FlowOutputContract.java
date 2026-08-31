package com.devmind.flow;

import com.devmind.project.model.RequirementEntity;

/**
 * 流程输出契约（CAP-14）：agent ↔ 流程层的唯一耦合点。
 * 流程型会话的 taskSpec 要求 agent 把结构化产出写到 worktree 约定路径，
 * 流程引擎在 session.completed 后读取并登记产物/文档。
 * taskSpec 首行的 [flow:*] 标记用于会话完成事件分流（区分分析/拆分与手工会话）。
 */
public final class FlowOutputContract {

    /** 输出目录（相对 worktree 根） */
    public static final String OUTPUT_DIR = ".devmind/output";
    /** 分析产出 */
    public static final String ANALYSIS_FILE = "analysis.md";
    /** 方案产出 */
    public static final String DESIGN_FILE = "design.md";
    /** 拆分产出（JSON 清单） */
    public static final String WI_PLAN_FILE = "wi-plan.json";

    /** taskSpec 首行标记：分析会话 */
    public static final String MARKER_ANALYZE = "[flow:analyze]";
    /** taskSpec 首行标记：拆分会话 */
    public static final String MARKER_SPLIT = "[flow:split]";

    private FlowOutputContract() {
    }

    /** 分析会话 taskSpec：读需求 → 产出 analysis.md（影响面/复杂度/建议）。 */
    public static String analysisSpec(RequirementEntity req) {
        return MARKER_ANALYZE + "\n"
                + "# 需求分析（REQ-" + req.getSeq() + " " + req.getTitle() + "）\n\n"
                + "请分析以下需求，输出影响面、复杂度评估与实现建议。\n\n"
                + "## 需求内容\n\n" + nullToEmpty(req.getDescription()) + "\n\n"
                + "## 输出要求\n\n"
                + "把分析结果写入 `" + OUTPUT_DIR + "/" + ANALYSIS_FILE + "`（Markdown，含："
                + "影响面（涉及的模块/表/接口）、复杂度评估、建议拆分方向、风险点）。"
                + "不要修改项目代码。";
    }

    /** 方案会话 taskSpec：读需求 → 产出 design.md（作为 DESIGN 型 Work Item 的 spec）。 */
    public static String designSpec(RequirementEntity req) {
        return "# 方案设计（REQ-" + req.getSeq() + " " + req.getTitle() + "）\n\n"
                + "请为以下需求设计技术方案。\n\n"
                + "## 需求内容\n\n" + nullToEmpty(req.getDescription()) + "\n\n"
                + "## 输出要求\n\n"
                + "把方案写入 `" + OUTPUT_DIR + "/" + DESIGN_FILE + "`（Markdown，含："
                + "总体思路、模块设计、数据模型变更、接口设计、测试要点）。"
                + "不要修改项目代码。";
    }

    /** 拆分会话 taskSpec：需求(+方案) → 产出 wi-plan.json 工作单元清单。 */
    public static String splitSpec(RequirementEntity req, String designContent) {
        StringBuilder sb = new StringBuilder();
        sb.append(MARKER_SPLIT).append("\n");
        sb.append("# 工作单元拆分（REQ-").append(req.getSeq()).append(' ').append(req.getTitle()).append("）\n\n");
        sb.append("请把以下需求拆分为可独立派发执行的工作单元（Work Item）。\n\n");
        sb.append("## 需求内容\n\n").append(nullToEmpty(req.getDescription())).append("\n\n");
        if (designContent != null && !designContent.isBlank()) {
            sb.append("## 已确认方案\n\n").append(designContent).append("\n\n");
        }
        sb.append("## 输出要求\n\n");
        sb.append("把拆分结果写入 `").append(OUTPUT_DIR).append('/').append(WI_PLAN_FILE)
                .append("`，JSON 数组，每个元素：\n");
        sb.append("- type: DESIGN / DEVELOPMENT / TEST / DOCUMENT / REVIEW 之一\n");
        sb.append("- title: 一句话标题\n");
        sb.append("- spec: 执行说明（将作为 agent 会话的 taskSpec，写清做什么、改哪里、验收标准）\n");
        sb.append("- dependsOn: 依赖的本清单内其他元素下标（0 起，数组，可空）\n\n");
        sb.append("示例：[{\"type\":\"DEVELOPMENT\",\"title\":\"...\",\"spec\":\"...\",\"dependsOn\":[]}]\n");
        sb.append("只输出该 JSON 文件，不要修改项目代码。");
        return sb.toString();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
