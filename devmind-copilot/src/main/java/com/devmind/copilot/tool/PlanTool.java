package com.devmind.copilot.tool;

import com.devmind.common.agent.tool.PlatformTool;
import com.devmind.common.agent.tool.ToolCall;
import com.devmind.common.agent.tool.ToolContext;
import com.devmind.common.agent.tool.ToolResult;
import com.devmind.common.agent.tool.ToolRisk;
import com.devmind.common.agent.tool.ToolSpec;
import com.devmind.copilot.config.CopilotProperties;
import com.devmind.copilot.service.PlanService;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 多步计划编排工具（CAP-76 FR-33，M7）：模型把 ≥2 步的写操作/有依赖的操作打包成一份
 * 有序计划提交；整份计划是一张确认单，一次人工确认后由 {@link PlanService} 按序执行、
 * 失败即停。归属 devmind-copilot 自身，不占用各能力模块的工具配额。
 */
@Component
public class PlanTool implements PlatformTool {

    private static final Logger log = LoggerFactory.getLogger(PlanTool.class);

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "goal": {"type": "string", "description": "计划目标（≤500字，一句话说明要达成什么）"},
                "steps": {
                  "type": "array",
                  "description": "有序步骤（2~plan-max-steps 步），按顺序执行，任一步失败即停、已完成步骤不回滚",
                  "maxItems": 8,
                  "items": {
                    "type": "object",
                    "properties": {
                      "tool": {"type": "string", "description": "工具名（工具目录里的 snake_case 名，禁 submit_plan）"},
                      "args": {"description": "该工具的参数对象，键名与目标工具 schema 一致；可用 ${stepN.field} 引用第 N 步返回的字段"},
                      "await": {
                        "type": "object",
                        "description": "可选：该步触发异步作业（构建/部署/测试/会话）且后续步骤依赖其完成时声明，平台轮询作业终态后再继续",
                        "properties": {
                          "kind": {"type": "string", "enum": ["BUILD", "SESSION", "DEPLOYMENT", "TEST_RUN"], "description": "作业类型"},
                          "idField": {"type": "string", "description": "该步返回结果里作业 id 的字段名（如 trigger_build 的 buildId、start_session 的 sessionId）"},
                          "timeoutMinutes": {"type": "integer", "minimum": 1, "description": "等待超时分钟数（默认 30）"}
                        },
                        "required": ["kind", "idField"]
                      },
                      "title": {"type": "string", "description": "步骤一句话说明（可选，给人看）"}
                    },
                    "required": ["tool", "args"]
                  }
                }
              },
              "required": ["goal", "steps"]
            }
            """;

    private final PlanService plans;
    private final ToolSpec spec;

    public PlanTool(PlanService plans, CopilotProperties props) {
        this.plans = plans;
        int timeout = Math.max(120,
                Math.max(2, props.getPlanMaxSteps()) * Math.max(30, props.getToolTimeoutSeconds()) + 60);
        this.spec = new ToolSpec(PlanService.TOOL_NAME, "提交多步计划", description(), SCHEMA,
                ToolRisk.WRITE, "DEVELOPER", false, "devmind-copilot", "CAP-76 M7",
                timeout, plans::riskOf);
    }

    private static String description() {
        return """
                提交一份多步计划：把 2 步及以上的写操作、或步骤间有数据依赖的操作打包成有序步骤，\
                整份计划经一次人工确认后由平台异步按序执行，任一步失败即停（已完成步骤不回滚），\
                执行完成后会推送结果到当前聊天。\
                步骤间引用：args 里写 ${stepN.field} 引用第 N 步（从 1 开始、只能引用更早的步骤）返回的字段，\
                例如 create_requirement 产出 requirementId/code、create_work_item 产出 workItemId/code、\
                start_session 产出 sessionId、trigger_build 产出 buildId。\
                重要：trigger_build/trigger_deploy/run_test/start_session 这类工具返回时作业刚开始（未执行完）；\
                后续步骤若依赖该作业的完成结果（如部署依赖构建成功、验证依赖会话跑完），\
                必须给该步声明 await（kind=BUILD/DEPLOYMENT/TEST_RUN/SESSION，idField=作业 id 字段名），\
                平台会等作业终态再继续。整份计划所有步骤必须在同一个项目内。\
                单步操作直接调对应工具，不要用本工具。""";
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext ctx) {
        return plans.execute(call.args(), ctx);
    }

    @Override
    public String preview(ToolCall call, ToolContext ctx) {
        try {
            return plans.preview(call.args(), ctx);
        } catch (RuntimeException e) {
            // 预览失败不挡确认单创建：执行期会全量重校验并给出具体错误
            log.warn("计划预览生成失败: {}", e.toString());
            return null;
        }
    }

    @Override
    public String resolveProjectId(Map<String, Object> args, ToolContext ctx) {
        return plans.commonProject(args, ctx);
    }
}
