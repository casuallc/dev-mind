package com.devmind.project.tool;

import com.devmind.common.agent.tool.PlatformTool;
import com.devmind.common.agent.tool.ToolResult;
import com.devmind.common.agent.tool.ToolRisk;
import com.devmind.common.agent.tool.ToolSpec;
import com.devmind.common.agent.tool.ToolText;
import com.devmind.project.RequirementService;
import com.devmind.project.WorkItemService;
import com.devmind.project.dto.RequirementRequest;
import com.devmind.project.dto.RequirementView;
import com.devmind.project.dto.WorkItemRequest;
import com.devmind.project.dto.WorkItemView;
import com.devmind.project.model.RequirementEntity;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * CAP-76 §6.2 项目域写工具：需求/工作项创建与状态流转。
 * 一律 WRITE 起，终态（DONE/CANCELLED）经 {@code riskFor} 升级 DANGEROUS（文本短码确认）。
 */
@Configuration
public class ProjectWriteTools {

    private static final String M = "M2";

    private final RequirementService requirements;
    private final WorkItemService workItems;

    public ProjectWriteTools(RequirementService requirements, WorkItemService workItems) {
        this.requirements = requirements;
        this.workItems = workItems;
    }

    @Bean
    public PlatformTool createRequirementTool() {
        ToolSpec spec = new ToolSpec("create_requirement", "创建需求",
                "在指定项目下创建需求（DRAFT 起步）。调用后生成确认单，用户确认后才落库。"
                        + "description 用纯文本即可；type 可空（默认 FEATURE）。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "projectId": {"type": "string", "maxLength": 64},
                            "title": {"type": "string", "maxLength": 240},
                            "description": {"type": "string", "maxLength": 20000},
                            "type": {"type": "string", "maxLength": 32},
                            "priority": {"type": "string", "maxLength": 32},
                            "assignee": {"type": "string", "maxLength": 64}
                          },
                          "required": ["title"]
                        }
                        """,
                ToolRisk.WRITE, "DEVELOPER", true, "devmind-project", M);
        return PlatformTool.of(spec, (call, ctx) -> {
            Map<String, Object> args = call.args();
            String pid = ProjectReadTools.projectId(args, ctx);
            if (pid == null) {
                return ProjectReadTools.projectRequired();
            }
            RequirementRequest req = new RequirementRequest(
                    ToolText.str(args, "title"), ToolText.str(args, "description"), null, null,
                    ToolText.str(args, "type"), ToolText.str(args, "priority"),
                    ToolText.str(args, "assignee"), null, null, null, null, null);
            RequirementView view = requirements.create(pid, req);
            String text = "已创建需求 " + view.code() + "：" + view.title();
            return ToolResult.ok(text, text, ToolResult.data(
                    "requirementId", view.id(), "code", view.code(), "projectId", pid));
        });
    }

    @Bean
    public PlatformTool updateRequirementTool() {
        ToolSpec spec = new ToolSpec("update_requirement", "修改需求",
                "修改需求的标题/描述/优先级/负责人（只改传入字段）。projectId 由服务端按需求反查；"
                        + "requirementId 可直接传展示编号（如 REQ-12，此时用当前绑定项目）。"
                        + "JIRA 来源需求的托管字段本地只读。调用后生成确认单。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "projectId": {"type": "string", "maxLength": 64},
                            "requirementId": {"type": "string", "maxLength": 64},
                            "seq": {"type": "integer", "minimum": 1},
                            "title": {"type": "string", "maxLength": 240},
                            "description": {"type": "string", "maxLength": 20000},
                            "priority": {"type": "string", "maxLength": 32},
                            "assignee": {"type": "string", "maxLength": 64}
                          }
                        }
                        """,
                ToolRisk.WRITE, "DEVELOPER", true, "devmind-project", M);
        return PlatformTool.of(spec, (call, ctx) -> {
            Map<String, Object> args = call.args();
            String requirementRef = ToolText.str(args, "requirementId");
            Long seq = args.get("seq") instanceof Number n ? n.longValue() : null;
            if (requirementRef == null && seq == null) {
                return ToolResult.fail(ToolResult.INVALID_ARGUMENTS, "请提供需求 id 或编号 REQ-<seq>");
            }
            RequirementEntity entity = requirements.resolveRef(
                    ProjectReadTools.projectId(args, ctx), requirementRef, seq);
            String requirementId = entity.getId();
            String title = ToolText.str(args, "title");
            String description = ToolText.str(args, "description");
            String priority = ToolText.str(args, "priority");
            String assignee = ToolText.str(args, "assignee");
            if (title == null && description == null && priority == null && assignee == null) {
                return ToolResult.fail(ToolResult.INVALID_ARGUMENTS,
                        "至少提供一个要修改的字段（title/description/priority/assignee）");
            }
            RequirementRequest req = new RequirementRequest(title, description, null, null, null,
                    priority, assignee, null, null, null, null, null);
            RequirementView view = requirements.update(entity.getProjectId(), requirementId, req);
            String text = "已更新需求 " + view.code() + "：" + view.title();
            return ToolResult.ok(text, text, ToolResult.data(
                    "requirementId", view.id(), "code", view.code(), "status", view.status()));
        }, args -> ProjectReadTools.requirementProject(requirements, args));
    }

    @Bean
    public PlatformTool updateRequirementStatusTool() {
        ToolSpec spec = new ToolSpec("update_requirement_status", "变更需求状态",
                "把需求推进到指定状态（DRAFT/ANALYZING/DESIGNING/IN_PROGRESS/ACCEPTANCE/DONE/CANCELLED）。"
                        + "requirementId 可直接传用户可见的展示编号（如 REQ-12；此时用当前绑定项目），"
                        + "也可用 seq。DONE/CANCELLED 属于终态，必须由用户文本短码确认；"
                        + "DONE 前若有未完结工作项会被拒绝。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "projectId": {"type": "string", "maxLength": 64},
                            "requirementId": {"type": "string", "maxLength": 64},
                            "seq": {"type": "integer", "minimum": 1},
                            "status": {"type": "string", "maxLength": 32}
                          },
                          "required": ["status"]
                        }
                        """,
                ToolRisk.WRITE, "DEVELOPER", true, "devmind-project", M, 0,
                args -> terminal(RequirementEntity.STATUS_DONE, RequirementEntity.STATUS_CANCELLED,
                        ToolText.str(args, "status")));
        return PlatformTool.of(spec, (call, ctx) -> {
            Map<String, Object> args = call.args();
            String requirementRef = ToolText.str(args, "requirementId");
            Long seq = args.get("seq") instanceof Number n ? n.longValue() : null;
            String status = normalizeStatus(ToolText.str(args, "status"));
            if ((requirementRef == null && seq == null) || status == null) {
                return ToolResult.fail(ToolResult.INVALID_ARGUMENTS,
                        "请提供需求 id 或编号 REQ-<seq>，以及目标状态");
            }
            RequirementEntity entity = requirements.resolveRef(
                    ProjectReadTools.projectId(args, ctx), requirementRef, seq);
            RequirementView view = requirements.updateStatus(entity.getProjectId(), entity.getId(), status);
            String text = "需求 " + view.code() + " 状态已变更为 " + view.status();
            return ToolResult.ok(text, text, ToolResult.data(
                    "requirementId", view.id(), "code", view.code(), "status", view.status()));
        }, args -> ProjectReadTools.requirementProject(requirements, args));
    }

    @Bean
    public PlatformTool createWorkItemTool() {
        ToolSpec spec = new ToolSpec("create_work_item", "创建工作项",
                "在需求下创建工作项（TODO 起步）。必须提供 projectId 与 requirementId（可用编号 REQ-<seq>）；"
                        + "spec 为工作说明，type 可空（默认 DEVELOPMENT）。调用后生成确认单。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "projectId": {"type": "string", "maxLength": 64},
                            "requirementId": {"type": "string", "maxLength": 64},
                            "title": {"type": "string", "maxLength": 240},
                            "spec": {"type": "string", "maxLength": 20000},
                            "type": {"type": "string", "maxLength": 32}
                          },
                          "required": ["projectId", "requirementId", "title"]
                        }
                        """,
                ToolRisk.WRITE, "DEVELOPER", true, "devmind-project", M);
        return PlatformTool.of(spec, (call, ctx) -> {
            Map<String, Object> args = call.args();
            String pid = ProjectReadTools.projectId(args, ctx);
            String requirementRef = ToolText.str(args, "requirementId");
            String title = ToolText.str(args, "title");
            if (pid == null || requirementRef == null || title == null) {
                return ToolResult.fail(ToolResult.INVALID_ARGUMENTS, "请提供项目、需求与工作项标题");
            }
            String requirementId = requirements.resolveRef(pid, requirementRef, null).getId();
            WorkItemView view = workItems.create(pid, requirementId, new WorkItemRequest(
                    ToolText.str(args, "type"), title, ToolText.str(args, "spec"), null, null, null));
            String text = "已创建工作项 " + view.code() + "：" + view.title();
            return ToolResult.ok(text, text, ToolResult.data(
                    "workItemId", view.id(), "code", view.code(), "requirementId", requirementId));
        });
    }

    @Bean
    public PlatformTool updateWorkItemStatusTool() {
        ToolSpec spec = new ToolSpec("update_work_item_status", "变更工作项状态",
                "把工作项推进到 TODO/IN_PROGRESS/BLOCKED/DONE/CANCELLED。CANCELLED 属于危险操作，"
                        + "必须由用户文本短码确认。需 projectId、requirementId、workItemId；后两者可用展示编号"
                        + "（REQ-<seq> / WI-<seq>）。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "projectId": {"type": "string", "maxLength": 64},
                            "requirementId": {"type": "string", "maxLength": 64},
                            "workItemId": {"type": "string", "maxLength": 64},
                            "status": {"type": "string", "maxLength": 32}
                          },
                          "required": ["projectId", "requirementId", "workItemId", "status"]
                        }
                        """,
                ToolRisk.WRITE, "DEVELOPER", true, "devmind-project", M, 0,
                args -> "CANCELLED".equals(normalizeStatus(ToolText.str(args, "status")))
                        ? ToolRisk.DANGEROUS : ToolRisk.WRITE);
        return PlatformTool.of(spec, (call, ctx) -> {
            Map<String, Object> args = call.args();
            String pid = ProjectReadTools.projectId(args, ctx);
            String requirementRef = ToolText.str(args, "requirementId");
            String workItemRef = ToolText.str(args, "workItemId");
            String status = normalizeStatus(ToolText.str(args, "status"));
            if (pid == null || requirementRef == null || workItemRef == null || status == null) {
                return ToolResult.fail(ToolResult.INVALID_ARGUMENTS, "请提供项目、需求、工作项与目标状态");
            }
            String requirementId = requirements.resolveRef(pid, requirementRef, null).getId();
            String workItemId = workItems.resolveRef(pid, workItemRef).getId();
            WorkItemView view = workItems.updateStatus(pid, requirementId, workItemId, status);
            String text = "工作项 " + view.code() + " 状态已变更为 " + view.status();
            return ToolResult.ok(text, text, ToolResult.data(
                    "workItemId", view.id(), "code", view.code(), "status", view.status()));
        });
    }

    // ---------------- 小助手 ----------------

    static String normalizeStatus(String status) {
        return status == null || status.isBlank() ? null
                : status.trim().toUpperCase(Locale.ROOT);
    }

    static ToolRisk terminal(String a, String b, String status) {
        String s = normalizeStatus(status);
        return a.equals(s) || b.equals(s) ? ToolRisk.DANGEROUS : ToolRisk.WRITE;
    }
}
