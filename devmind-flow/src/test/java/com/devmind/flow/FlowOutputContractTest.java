package com.devmind.flow;

import com.devmind.project.model.RequirementEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FlowOutputContract spec 注入矩阵（CAP-37 FR-03）：
 * designSpec 带/不带分析结论；splitSpec 三分支（有方案 / 无方案有分析 / 皆无）。
 */
class FlowOutputContractTest {

    private RequirementEntity req() {
        RequirementEntity r = new RequirementEntity();
        r.setSeq(12L);
        r.setTitle("支持 xx");
        r.setDescription("需求描述正文");
        return r;
    }

    @Test
    void 方案spec注入分析结论() {
        String spec = FlowOutputContract.designSpec(req(), "## 影响面\n涉及用户表");
        assertTrue(spec.contains("## 需求内容"));
        assertTrue(spec.contains("## 需求分析结论"));
        assertTrue(spec.contains("涉及用户表"));
        assertTrue(spec.contains(FlowOutputContract.DESIGN_FILE));
    }

    @Test
    void 方案spec无分析时省略该节() {
        String spec = FlowOutputContract.designSpec(req(), null);
        assertFalse(spec.contains("需求分析结论"));
        assertTrue(spec.contains(FlowOutputContract.DESIGN_FILE));
        // 空白分析同样省略
        assertFalse(FlowOutputContract.designSpec(req(), "  ").contains("需求分析结论"));
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

    @Test
    void 分析spec形态不变() {
        String spec = FlowOutputContract.analysisSpec(req());
        assertTrue(spec.startsWith(FlowOutputContract.MARKER_ANALYZE));
        assertTrue(spec.contains(FlowOutputContract.ANALYSIS_FILE));
        assertTrue(spec.contains("需求描述正文"));
    }
}
