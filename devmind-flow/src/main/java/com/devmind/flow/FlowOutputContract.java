package com.devmind.flow;

import com.devmind.project.model.RequirementEntity;

import java.util.List;

/**
 * 流程输出契约（CAP-14/CAP-37）：agent ↔ 流程层的唯一耦合点。
 * 流程型会话的 taskSpec 要求 agent 把结构化产出写到 worktree 约定路径，
 * runner 在进程退出前回传服务端落 session_outputs（CAP-37 FR-01），
 * 流程引擎在 session.completed 后读取并登记产物/文档。
 * taskSpec 首行的 [flow:*] 标记用于会话完成事件分流（区分分析/拆分与手工会话）。
 */
public final class FlowOutputContract {

    /** 输出目录（相对 worktree 根；runner 侧 OutputUploader 同名常量同步） */
    public static final String OUTPUT_DIR = ".devmind/output";
    /** 分析产出 */
    public static final String ANALYSIS_FILE = "analysis.md";
    /** 方案产出 */
    public static final String DESIGN_FILE = "design.md";
    /** 拆分产出（JSON 清单） */
    public static final String WI_PLAN_FILE = "wi-plan.json";
    /** CAP-52 开发会话收尾产出（逐条说明完成情况） */
    public static final String DEV_SUMMARY_FILE = "dev-summary.md";

    /** taskSpec 首行标记：分析会话（存量，CAP-52 起由规划会话取代） */
    public static final String MARKER_ANALYZE = "[flow:analyze]";
    /** taskSpec 首行标记：拆分会话（存量，CAP-52 起由规划会话取代） */
    public static final String MARKER_SPLIT = "[flow:split]";
    /** CAP-52 taskSpec 首行标记：规划会话（分析 + 方案 + 工作单元三合一） */
    public static final String MARKER_PLAN = "[flow:plan]";
    /** CAP-52 taskSpec 首行标记：需求级开发会话（按清单一次做完） */
    public static final String MARKER_DEV = "[flow:dev]";

    /** 注入 spec 的背景材料截断上限（防多份上游产出叠加膨胀 token） */
    static final int CONTEXT_TRUNCATE_CHARS = 4000;

    private FlowOutputContract() {
    }

    /** 拆分会话 taskSpec：需求(+方案+分析) → 产出 wi-plan.json 工作单元清单。
     *  存量契约：CAP-52 起阶段入口已删，仅 DESIGN 型工作单元的手工会话（
     *  {@code autoSplit} 兜底路径）还会用它。 */
    public static String splitSpec(RequirementEntity req, String designContent, String analysisContent) {
        StringBuilder sb = new StringBuilder();
        sb.append(MARKER_SPLIT).append("\n");
        sb.append("# 工作单元拆分（REQ-").append(req.getSeq()).append(' ').append(req.getTitle()).append("）\n\n");
        sb.append("请把以下需求拆分为可独立派发执行的工作单元（Work Item）。\n\n");
        sb.append("## 需求内容\n\n").append(nullToEmpty(req.getDescription())).append("\n\n");
        if (designContent != null && !designContent.isBlank()) {
            sb.append("## 已确认方案\n\n").append(designContent).append("\n\n");
        }
        if (analysisContent != null && !analysisContent.isBlank()) {
            // 有方案时分析作背景补充（截断防膨胀）；无方案时分析是唯一上游上下文，给全文
            String analysis = designContent == null || designContent.isBlank()
                    ? analysisContent : truncate(analysisContent);
            sb.append("## 需求分析结论（背景）\n\n").append(analysis).append("\n\n");
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

    // ---------------- CAP-52：三合一规划会话 + 需求级开发会话 ----------------

    /** 开发会话清单项（planSpec 产出固化后的工作单元，供 {@link #devSpec} 渲染）。 */
    public record DevItem(int seq, String type, String title, String spec, List<String> dependsOn) {
    }

    /**
     * CAP-52 FR-01 规划会话 taskSpec：一次会话产出分析 + 方案 + 工作单元三个文件。
     *
     * <p>为什么三合一：三份产出本是同一条推理链（分析 → 方案 → 拆分），分三个会话时服务端
     * 只能把上游产出当字符串重塞进下游 spec，且每个会话都要重注入一遍知识库/skills/附件。
     * 合成一条对话上下文后，上游结论天然共享，注入与请求数都降到 1/N。</p>
     *
     * @param skipAnalysis 存量「跳过分析」标记 → 不要求该文件（产出范围收窄，不再是阶段门禁）
     * @param skipDesign   存量「跳过方案」标记 → 不要求该文件
     */
    public static String planSpec(RequirementEntity req, boolean skipAnalysis, boolean skipDesign) {
        StringBuilder sb = new StringBuilder();
        sb.append(MARKER_PLAN).append('\n');
        sb.append("# 需求规划（REQ-").append(req.getSeq()).append(' ').append(req.getTitle()).append("）\n\n");
        sb.append("请一次性完成这个需求的**分析、方案设计与工作单元拆分**，三份产出写进约定文件。\n");
        sb.append("本次只做规划与拆分，**不要修改项目代码**。\n\n");
        sb.append("## 需求内容\n\n").append(nullToEmpty(req.getDescription())).append("\n\n");
        sb.append("## 输出要求\n\n");
        if (!skipAnalysis) {
            sb.append("### 1. `").append(OUTPUT_DIR).append('/').append(ANALYSIS_FILE).append("`\n\n")
                    .append("需求分析（Markdown）：影响面（涉及的模块/表/接口）、复杂度评估、风险点。\n")
                    .append("这份文件写给**人**看，供评审决策。\n\n");
        }
        if (!skipDesign) {
            sb.append("### ").append(skipAnalysis ? "1" : "2").append(". `").append(OUTPUT_DIR).append('/')
                    .append(DESIGN_FILE).append("`\n\n")
                    .append("技术方案（Markdown）：总体思路、模块设计、数据模型变更、接口设计、测试要点。\n")
                    .append("这份文件写给**人**看，供评审决策。\n\n");
        }
        sb.append("### ").append(skipAnalysis && skipDesign ? "1"
                        : skipAnalysis || skipDesign ? "2" : "3").append(". `")
                .append(OUTPUT_DIR).append('/').append(WI_PLAN_FILE).append("`\n\n");
        sb.append("工作单元清单（JSON 数组），每个元素：\n");
        sb.append("- type: DESIGN / DEVELOPMENT / TEST / DOCUMENT / REVIEW 之一（通常为 DEVELOPMENT）\n");
        sb.append("- title: 一句话标题\n");
        sb.append("- spec: 执行说明，见下方**自包含**要求\n");
        sb.append("- dependsOn: 依赖的本清单内其他元素下标（0 起，数组，可空）\n\n");
        sb.append("**拆分粒度（硬要求，违反会被平台拒绝固化）**：\n");
        sb.append("- 产出 **1~3 个**工作单元，最多不超过 5 个；宁可少拆，不要凑数；\n");
        sb.append("- 一个工作单元 = **一次可独立验收的改动**（一个功能点 / 一处缺陷 / 一次改造）；\n");
        sb.append("- **禁止**按文件、按接口、按方法、按「先写实现再写测试」这类技术动作拆分——\n");
        sb.append("  这些是一个工作单元内部的步骤，不是工作单元；\n");
        sb.append("- 只有存在**真实先后依赖**时才用 dependsOn，能并行做完的就不要造依赖。\n\n");
        sb.append("**spec 必须自包含（关键）**：每个 `spec` 会被直接当作开发会话的任务说明，\n");
        sb.append("而开发会话**看不到本对话、知识库与需求附件**。所以 spec 要写清：\n");
        sb.append("改哪些文件/模块、具体做什么、遵循的项目约定与命名、怎么算完成（验收标准：\n");
        sb.append("跑什么命令、看什么结果）。凡是本对话里已经确定的结论，都要落到 spec 文字里。\n\n");
        sb.append("示例：[{\"type\":\"DEVELOPMENT\",\"title\":\"...\",\"spec\":\"...\",\"dependsOn\":[]}]\n\n");
        sb.append("完成后只输出这三个（按上面的产出范围）文件到 `").append(OUTPUT_DIR)
                .append("/`，不要修改项目代码。\n");
        return sb.toString();
    }

    /**
     * CAP-52 FR-04 需求级开发会话 taskSpec：一次会话按清单顺序做完整个需求。
     *
     * <p>不再逐工作单元派发会话——那样每个 WI 都要重注入一遍上下文、各自重新理解需求，
     * 请求数随 WI 数线性增长；需求级一次做完后，配合 CAP-51 的需求工作树，改动天然累积在
     * 同一条分支上。</p>
     */
    public static String devSpec(RequirementEntity req, List<DevItem> items) {
        StringBuilder sb = new StringBuilder();
        sb.append(MARKER_DEV).append('\n');
        sb.append("# 需求开发（REQ-").append(req.getSeq()).append(' ').append(req.getTitle()).append("）\n\n");
        sb.append("请按下面清单的顺序**把这个需求做完**，直接修改项目代码。\n");
        sb.append("本会话没有知识库与需求附件注入——清单里每条 spec 已写清要做什么，")
                .append("按 spec 执行；spec 没写到的约定，先读仓库现有代码与配置确认，不要臆测。\n\n");
        sb.append("## 需求内容\n\n").append(nullToEmpty(req.getDescription())).append("\n\n");
        sb.append("## 工作单元清单（共 ").append(items.size()).append(" 条，按序完成）\n\n");
        for (DevItem it : items) {
            sb.append("### ").append(it.seq()).append(". ").append(it.title())
                    .append("（").append(it.type()).append("）\n\n");
            if (it.dependsOn() != null && !it.dependsOn().isEmpty()) {
                sb.append("依赖：先完成 ").append(String.join("、", it.dependsOn())).append("\n\n");
            }
            sb.append(nullToEmpty(it.spec())).append("\n\n");
        }
        sb.append("## 收尾要求\n\n");
        sb.append("1. 每条做完后确认改动符合该条 spec 的验收标准；无法完成的**不要卡住**——跳过，")
                .append("并在下面的产出里写清原因；\n");
        sb.append("2. 全部完成后把工作树内的改动提交到当前分支（`git add -A && git commit`），")
                .append("不要 push、不要切分支；\n");
        sb.append("3. 写 `").append(OUTPUT_DIR).append('/').append(DEV_SUMMARY_FILE)
                .append("`（Markdown）：逐条列出「做了什么、改了哪些文件、怎么验证的、遗留问题」，")
                .append("以及跳过的条目与原因。\n");
        return sb.toString();
    }

    private static String truncate(String s) {
        return s.length() <= CONTEXT_TRUNCATE_CHARS
                ? s : s.substring(0, CONTEXT_TRUNCATE_CHARS) + "\n\n（……截断，完整内容见分析文档）";
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
