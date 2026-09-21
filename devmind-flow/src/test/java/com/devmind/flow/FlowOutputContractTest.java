package com.devmind.flow;

import com.devmind.project.model.RequirementEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FlowOutputContract spec 注入矩阵：splitSpec 三分支（存量 DESIGN WI 兜底路径，CAP-37 FR-03）
 * + CAP-52 的 planSpec（三份产出/产出范围/spec 自包含要求）与 devSpec（清单渲染/收尾要求）。
 */
class FlowOutputContractTest {

    private static final String LEAN_NOTE = "没有知识库与需求附件注入";

    private RequirementEntity req() {
        RequirementEntity r = new RequirementEntity();
        r.setSeq(12L);
        r.setTitle("支持 xx");
        r.setDescription("需求描述正文");
        return r;
    }

    // ---------------- CAP-52：三合一规划 spec ----------------

    @Test
    void 规划spec要求三份产出与粒度护栏() {
        String spec = FlowOutputContract.planSpec(req(), false, false);
        assertTrue(spec.startsWith(FlowOutputContract.MARKER_PLAN));
        assertTrue(spec.contains("需求描述正文"));
        assertTrue(spec.contains(FlowOutputContract.ANALYSIS_FILE));
        assertTrue(spec.contains(FlowOutputContract.DESIGN_FILE));
        assertTrue(spec.contains(FlowOutputContract.WI_PLAN_FILE));
        // 粒度硬约束与自包含要求必须写进 prompt——这是「少拆分、少请求」的唯一执行手段
        assertTrue(spec.contains("1~3 个"));
        assertTrue(spec.contains("最多不超过 5 个"));
        assertTrue(spec.contains("禁止"));
        assertTrue(spec.contains("spec 必须自包含"));
        assertTrue(spec.contains("不要修改项目代码"));
    }

    @Test
    void 规划spec跳过阶段时收窄产出范围() {
        // 存量「跳过」标记 → 产出范围：只要求清单，编号从 1 起
        String spec = FlowOutputContract.planSpec(req(), true, true);
        assertFalse(spec.contains(FlowOutputContract.ANALYSIS_FILE));
        assertFalse(spec.contains(FlowOutputContract.DESIGN_FILE));
        assertTrue(spec.contains("### 1. `" + FlowOutputContract.OUTPUT_DIR + "/"
                + FlowOutputContract.WI_PLAN_FILE + "`"));
    }

    @Test
    void 规划spec只跳方案时编号顺延() {
        String spec = FlowOutputContract.planSpec(req(), false, true);
        assertTrue(spec.contains(FlowOutputContract.ANALYSIS_FILE));
        assertTrue(spec.contains("### 2. `" + FlowOutputContract.OUTPUT_DIR + "/"
                + FlowOutputContract.WI_PLAN_FILE + "`"));
    }

    // ---------------- CAP-52：需求级开发 spec ----------------

    @Test
    void 开发spec渲染清单与依赖并声明瘦上下文() {
        String spec = FlowOutputContract.devSpec(req(), java.util.List.of(
                new FlowOutputContract.DevItem(1, "DEVELOPMENT", "后端接口", "改 alert_rule", java.util.List.of()),
                new FlowOutputContract.DevItem(2, "TEST", "补测试", "覆盖新分支", java.util.List.of("#1"))));
        assertTrue(spec.startsWith(FlowOutputContract.MARKER_DEV));
        assertTrue(spec.contains("工作单元清单（共 2 条，按序完成）"));
        assertTrue(spec.contains("### 1. 后端接口（DEVELOPMENT）"));
        assertTrue(spec.contains("依赖：先完成 #1"));
        assertTrue(spec.contains("覆盖新分支"));
        // 执行会话不注入知识/附件：必须在 prompt 里说清（免得 agent 去找不存在的上下文）
        assertTrue(spec.contains(LEAN_NOTE));
        assertTrue(spec.contains(FlowOutputContract.DEV_SUMMARY_FILE));
    }

    @Test
    void 拆分spec有方案时双注入且分析截断() {
        String longAnalysis = "x".repeat(FlowOutputContract.CONTEXT_TRUNCATE_CHARS + 500);
        String spec = FlowOutputContract.splitSpec(req(), "## 方案正文", longAnalysis);
        assertTrue(spec.startsWith(FlowOutputContract.MARKER_SPLIT));
        assertTrue(spec.contains("## 已确认方案"));
        assertTrue(spec.contains("## 方案正文"));
        assertTrue(spec.contains("## 需求分析结论（背景）"));
        assertTrue(spec.contains("截断"), "有方案时分析应截断防膨胀");
        assertFalse(spec.contains(longAnalysis), "全文不应原样注入");
    }

    @Test
    void 拆分spec无方案时注入分析全文() {
        String analysis = "a".repeat(FlowOutputContract.CONTEXT_TRUNCATE_CHARS + 500);
        String spec = FlowOutputContract.splitSpec(req(), null, analysis);
        assertFalse(spec.contains("## 已确认方案"));
        assertTrue(spec.contains(analysis), "无方案时分析是唯一上游上下文，给全文");
    }

    @Test
    void 拆分spec皆无上游时保持原样() {
        String spec = FlowOutputContract.splitSpec(req(), null, null);
        assertTrue(spec.startsWith(FlowOutputContract.MARKER_SPLIT));
        assertFalse(spec.contains("已确认方案"));
        assertFalse(spec.contains("需求分析结论"));
        assertTrue(spec.contains(FlowOutputContract.WI_PLAN_FILE));
    }
}
