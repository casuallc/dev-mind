package com.devmind.project.tool;

import com.devmind.common.agent.tool.PlatformTool;
import com.devmind.common.agent.tool.ToolCall;
import com.devmind.common.agent.tool.ToolContext;
import com.devmind.common.agent.tool.ToolResult;
import com.devmind.common.agent.tool.ToolRisk;
import com.devmind.common.agent.tool.ToolSpec;
import com.devmind.common.agent.tool.ToolText;
import com.devmind.project.DesignService;
import com.devmind.project.EnvironmentService;
import com.devmind.project.ProjectService;
import com.devmind.project.RequirementService;
import com.devmind.project.WorkItemService;
import com.devmind.project.WorkItemSessionLookup;
import com.devmind.project.dto.DesignView;
import com.devmind.project.dto.EnvironmentView;
import com.devmind.project.dto.ProjectView;
import com.devmind.project.dto.RequirementView;
import com.devmind.project.dto.WorkItemView;
import com.devmind.project.model.EnvironmentEntity;
import com.devmind.project.model.RequirementEntity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * CAP-76 §6.1 项目域只读工具：项目/需求/工作项/设计/环境。
 * 工具只调本模块 Service（FR-05），对象级工具用对象自身 projectId 做权限归属（FR-21）。
 */
@Configuration
public class ProjectReadTools {

    private static final String M = "M1";

    private final ProjectService projects;
    private final RequirementService requirements;
    private final WorkItemService workItems;
    private final DesignService designs;
    private final EnvironmentService environments;
    private final ObjectProvider<WorkItemSessionLookup> sessionLookup;

    public ProjectReadTools(ProjectService projects, RequirementService requirements,
                            WorkItemService workItems, DesignService designs,
                            EnvironmentService environments,
                            ObjectProvider<WorkItemSessionLookup> sessionLookup) {
        this.projects = projects;
        this.requirements = requirements;
        this.workItems = workItems;
        this.designs = designs;
        this.environments = environments;
        this.sessionLookup = sessionLookup;
    }

    @Bean
    public PlatformTool listProjectsTool() {
        ToolSpec spec = new ToolSpec("list_projects", "查询项目列表",
                "查询当前账号可见的项目（id/名称/状态/默认分支）。用户提到模糊项目名、或写操作需要 projectId "
                        + "但尚未确定项目时先调用。可用 keyword 按名称/标签过滤。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "keyword": {"type": "string", "maxLength": 100},
                            "status": {"type": "string", "maxLength": 32},
                            "limit": {"type": "integer", "default": 20, "minimum": 1, "maximum": 50}
                          }
                        }
                        """,
                ToolRisk.READ, "VIEWER", false, "devmind-project", M);
        return PlatformTool.of(spec, (call, ctx) -> {
            Map<String, Object> args = call.args();
            List<ProjectView> list = projects.list(ToolText.str(args, "status"), null,
                    ToolText.str(args, "keyword"), ToolText.limit(args));
            if (list.isEmpty()) {
                return ToolResult.ok("没有匹配的项目。");
            }
            StringBuilder sb = new StringBuilder("共 " + list.size() + " 个项目：");
            List<Map<String, Object>> rows = new ArrayList<>();
            for (ProjectView p : list) {
                sb.append("\n- ").append(p.name()).append("（id=").append(p.id())
                        .append("，状态=").append(p.status())
                        .append("，默认分支=").append(p.defaultBranch()).append("）");
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", p.id());
                row.put("name", p.name());
                row.put("status", p.status());
                row.put("defaultBranch", p.defaultBranch());
                if (p.tags() != null && !p.tags().isEmpty()) {
                    row.put("tags", p.tags());
                }
                rows.add(row);
            }
            return ToolResult.ok(sb.toString(), sb.toString(), ToolResult.data("projects", rows));
        });
    }

    @Bean
    public PlatformTool listRequirementsTool() {
        ToolSpec spec = new ToolSpec("list_requirements", "查询需求列表",
                "列出某项目下的需求（code/标题/状态/类型/优先级）。status 支持 OPEN=未完结；"
                        + "keyword 匹配标题或外部编号。需要项目 id，默认取当前绑定项目。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "projectId": {"type": "string", "maxLength": 64},
                            "status": {"type": "string", "maxLength": 32},
                            "keyword": {"type": "string", "maxLength": 100},
                            "limit": {"type": "integer", "default": 20, "minimum": 1, "maximum": 50}
                          }
                        }
                        """,
                ToolRisk.READ, "VIEWER", true, "devmind-project", M);
        return PlatformTool.of(spec, (call, ctx) -> {
            Map<String, Object> args = call.args();
            String projectId = projectId(args, ctx);
            if (projectId == null) {
                return projectRequired();
            }
            var page = requirements.list(projectId, ToolText.str(args, "status"), null, null,
                    ToolText.str(args, "keyword"), "seq", "desc", 0, ToolText.limit(args));
            if (page.items().isEmpty()) {
                return ToolResult.ok("该项目下没有匹配的需求。");
            }
            StringBuilder sb = new StringBuilder("共 " + page.total() + " 条需求（显示 " + page.items().size() + " 条）：");
            List<Map<String, Object>> rows = new ArrayList<>();
            for (RequirementView r : page.items()) {
                sb.append("\n- ").append(r.code()).append(" [").append(r.status()).append("] ")
                        .append(r.title()).append("（").append(r.type()).append("）");
                if (r.priority() != null && !r.priority().isBlank()) {
                    sb.append(" 优先级=").append(r.priority());
                }
                if (r.assignee() != null && !r.assignee().isBlank()) {
                    sb.append(" 负责人=").append(r.assignee());
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", r.id());
                row.put("code", r.code());
                row.put("title", r.title());
                row.put("status", r.status());
                row.put("type", r.type());
                if (r.priority() != null) {
                    row.put("priority", r.priority());
                }
                rows.add(row);
            }
            return ToolResult.ok(sb.toString(), sb.toString(),
                    ToolResult.data("total", page.total(), "requirements", rows));
        });
    }

    @Bean
    public PlatformTool getRequirementTool() {
        ToolSpec spec = new ToolSpec("get_requirement", "查询需求详情",
                "按 requirementId 或项目内编号查询需求详情，含状态、描述、工作项与设计摘要。"
                        + "requirementId 可直接传展示编号（如 REQ-12），也可用 seq=12；用编号时必须能确定 projectId。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "projectId": {"type": "string", "maxLength": 64},
                            "requirementId": {"type": "string", "maxLength": 64},
                            "seq": {"type": "integer", "minimum": 1}
                          }
                        }
                        """,
                ToolRisk.READ, "VIEWER", false, "devmind-project", M);
        return PlatformTool.of(spec, (call, ctx) -> {
            Map<String, Object> args = call.args();
            String requirementId = ToolText.str(args, "requirementId");
            Long seq = args.get("seq") instanceof Number n ? n.longValue() : null;
            if (requirementId == null && seq == null) {
                return ToolResult.fail(ToolResult.INVALID_ARGUMENTS,
                        "请提供 requirementId（内部 id 或 REQ-12），或提供项目与项目内编号 seq");
            }
            RequirementEntity entity = requirements.resolveRef(projectId(args, ctx), requirementId, seq);
            String projectId = entity.getProjectId();
            RequirementView view = requirements.get(projectId, entity.getId());
            List<WorkItemView> wis = workItems.list(projectId, view.id());
            List<DesignView> ds = designs.list(projectId, view.id());

            StringBuilder sb = new StringBuilder();
            sb.append(view.code()).append(" ").append(view.title())
                    .append("\n状态: ").append(view.status())
                    .append(" · 类型: ").append(view.type());
            if (view.priority() != null) {
                sb.append(" · 优先级: ").append(view.priority());
            }
            if (view.assignee() != null) {
                sb.append(" · 负责人: ").append(view.assignee());
            }
            sb.append("\n来源: ").append(view.source());
            if (view.dueDate() != null) {
                sb.append(" · 截止: ").append(ToolText.date(view.dueDate()));
            }
            if (view.description() != null && !view.description().isBlank()) {
                sb.append("\n描述: ").append(ToolText.truncate(view.description(), 800));
            }
            sb.append("\n工作项 (").append(wis.size()).append("):");
            if (wis.isEmpty()) {
                sb.append(" 无");
            }
            for (WorkItemView w : wis) {
                sb.append("\n- ").append(w.code()).append(" [").append(w.status()).append("] ")
                        .append(w.title());
            }
            sb.append("\n设计 (").append(ds.size()).append("):");
            if (ds.isEmpty()) {
                sb.append(" 无");
            }
            for (DesignView d : ds) {
                sb.append("\n- v").append(d.version()).append(" [").append(d.status()).append("]")
                        .append(d.docId() == null ? "" : " docId=" + d.docId());
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("requirementId", view.id());
            data.put("code", view.code());
            data.put("title", view.title());
            data.put("status", view.status());
            data.put("type", view.type());
            data.put("projectId", projectId);
            data.put("workItemCount", wis.size());
            data.put("designCount", ds.size());
            return ToolResult.ok(sb.toString(), sb.toString(), data);
        }, args -> requirementProject(requirements, args));
    }

    @Bean
    public PlatformTool listWorkItemsTool() {
        ToolSpec spec = new ToolSpec("list_work_items", "查询工作项列表",
                "列出某需求下的工作项（code/标题/状态/类型）。必须同时提供 projectId 与 requirementId"
                        + "（requirementId 可用展示编号 REQ-<seq>）。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "projectId": {"type": "string", "maxLength": 64},
                            "requirementId": {"type": "string", "maxLength": 64},
                            "status": {"type": "string", "maxLength": 32},
                            "keyword": {"type": "string", "maxLength": 100},
                            "limit": {"type": "integer", "default": 20, "minimum": 1, "maximum": 50}
                          },
                          "required": ["projectId", "requirementId"]
                        }
                        """,
                ToolRisk.READ, "VIEWER", true, "devmind-project", M);
        return PlatformTool.of(spec, (call, ctx) -> {
            Map<String, Object> args = call.args();
            String projectId = projectId(args, ctx);
            String requirementRef = ToolText.str(args, "requirementId");
            if (projectId == null || requirementRef == null) {
                return ToolResult.fail(ToolResult.INVALID_ARGUMENTS, "请提供项目与需求");
            }
            String requirementId = requirements.resolveRef(projectId, requirementRef, null).getId();
            String status = ToolText.str(args, "status");
            String keyword = ToolText.str(args, "keyword");
            List<WorkItemView> all = workItems.list(projectId, requirementId);
            List<WorkItemView> list = all.stream()
                    .filter(w -> status == null || status.equalsIgnoreCase(w.status()))
                    .filter(w -> keyword == null || (w.title() != null
                            && w.title().toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT))))
                    .limit(ToolText.limit(args))
                    .toList();
            if (list.isEmpty()) {
                return ToolResult.ok("没有匹配的工作项。");
            }
            StringBuilder sb = new StringBuilder("共 " + list.size() + " 个工作项：");
            List<Map<String, Object>> rows = new ArrayList<>();
            for (WorkItemView w : list) {
                sb.append("\n- ").append(w.code()).append(" [").append(w.status()).append("] ")
                        .append(w.title()).append("（").append(w.type()).append("）");
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", w.id());
                row.put("code", w.code());
                row.put("title", w.title());
                row.put("status", w.status());
                row.put("type", w.type());
                rows.add(row);
            }
            return ToolResult.ok(sb.toString(), sb.toString(), ToolResult.data("workItems", rows));
        });
    }

    @Bean
    public PlatformTool getWorkItemTool() {
        ToolSpec spec = new ToolSpec("get_work_item", "查询工作项详情",
                "查询工作项详情（状态/说明/类型）及关联会话。需 projectId、requirementId、workItemId；"
                        + "后两者都可用展示编号（REQ-<seq> / WI-<seq>）。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "projectId": {"type": "string", "maxLength": 64},
                            "requirementId": {"type": "string", "maxLength": 64},
                            "workItemId": {"type": "string", "maxLength": 64}
                          },
                          "required": ["projectId", "requirementId", "workItemId"]
                        }
                        """,
                ToolRisk.READ, "VIEWER", true, "devmind-project", M);
        return PlatformTool.of(spec, (call, ctx) -> {
            Map<String, Object> args = call.args();
            String projectId = projectId(args, ctx);
            String requirementRef = ToolText.str(args, "requirementId");
            String workItemRef = ToolText.str(args, "workItemId");
            if (projectId == null || requirementRef == null || workItemRef == null) {
                return ToolResult.fail(ToolResult.INVALID_ARGUMENTS, "请提供项目、需求与工作项");
            }
            String requirementId = requirements.resolveRef(projectId, requirementRef, null).getId();
            String workItemId = workItems.resolveRef(projectId, workItemRef).getId();
            WorkItemView w = workItems.get(projectId, requirementId, workItemId);
            StringBuilder sb = new StringBuilder();
            sb.append(w.code()).append(" ").append(w.title())
                    .append("\n状态: ").append(w.status())
                    .append(" · 类型: ").append(w.type());
            if (w.ownerId() != null) {
                sb.append(" · 负责人: ").append(w.ownerId());
            }
            if (w.spec() != null && !w.spec().isBlank()) {
                sb.append("\n说明: ").append(ToolText.truncate(w.spec(), 800));
            }
            WorkItemSessionLookup lookup = sessionLookup.getIfAvailable();
            int sessionCount = 0;
            if (lookup != null) {
                List<WorkItemSessionLookup.SessionBrief> sessions = lookup.sessionsFor(w.id());
                sessionCount = sessions.size();
                sb.append("\n关联会话 (").append(sessionCount).append("):");
                if (sessions.isEmpty()) {
                    sb.append(" 无");
                }
                for (WorkItemSessionLookup.SessionBrief s : sessions) {
                    sb.append("\n- ").append(s.id().length() <= 8 ? s.id() : s.id().substring(0, 8))
                            .append(" [").append(s.status()).append("] ")
                            .append(ToolText.oneLine(s.taskSpec(), 80));
                }
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("workItemId", w.id());
            data.put("code", w.code());
            data.put("title", w.title());
            data.put("status", w.status());
            data.put("type", w.type());
            data.put("projectId", projectId);
            data.put("sessionCount", sessionCount);
            return ToolResult.ok(sb.toString(), sb.toString(), data);
        });
    }

    @Bean
    public PlatformTool listDesignsTool() {
        ToolSpec spec = new ToolSpec("list_designs", "查询设计版本列表",
                "列出某需求下的设计版本（版本号/状态/文档 id）。必须同时提供 projectId 与 requirementId"
                        + "（requirementId 可用展示编号 REQ-<seq>）。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "projectId": {"type": "string", "maxLength": 64},
                            "requirementId": {"type": "string", "maxLength": 64},
                            "limit": {"type": "integer", "default": 20, "minimum": 1, "maximum": 50}
                          },
                          "required": ["projectId", "requirementId"]
                        }
                        """,
                ToolRisk.READ, "VIEWER", true, "devmind-project", M);
        return PlatformTool.of(spec, (call, ctx) -> {
            Map<String, Object> args = call.args();
            String projectId = projectId(args, ctx);
            String requirementRef = ToolText.str(args, "requirementId");
            if (projectId == null || requirementRef == null) {
                return ToolResult.fail(ToolResult.INVALID_ARGUMENTS, "请提供项目与需求");
            }
            String requirementId = requirements.resolveRef(projectId, requirementRef, null).getId();
            List<DesignView> list = designs.list(projectId, requirementId).stream()
                    .limit(ToolText.limit(args)).toList();
            if (list.isEmpty()) {
                return ToolResult.ok("该需求还没有设计版本。");
            }
            StringBuilder sb = new StringBuilder("共 " + list.size() + " 个设计版本：");
            List<Map<String, Object>> rows = new ArrayList<>();
            for (DesignView d : list) {
                sb.append("\n- v").append(d.version()).append(" [").append(d.status()).append("]")
                        .append(d.docId() == null ? "" : " docId=" + d.docId())
                        .append(" · ").append(ToolText.time(d.createdAt()));
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", d.id());
                row.put("version", d.version());
                row.put("status", d.status());
                if (d.docId() != null) {
                    row.put("docId", d.docId());
                }
                rows.add(row);
            }
            return ToolResult.ok(sb.toString(), sb.toString(), ToolResult.data("designs", rows));
        });
    }

    @Bean
    public PlatformTool listEnvironmentsTool() {
        ToolSpec spec = new ToolSpec("list_environments", "查询可部署环境",
                "列出项目配置的环境（DEV/TEST/STAGING/PROD）。用于回答能否部署到某环境；本期不提供部署写操作。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "projectId": {"type": "string", "maxLength": 64}
                          }
                        }
                        """,
                ToolRisk.READ, "VIEWER", true, "devmind-project", M);
        return PlatformTool.of(spec, (call, ctx) -> {
            String projectId = projectId(call.args(), ctx);
            if (projectId == null) {
                return projectRequired();
            }
            List<EnvironmentView> list = environments.list(projectId);
            if (list.isEmpty()) {
                return ToolResult.ok("该项目还没有配置可部署环境。");
            }
            StringBuilder sb = new StringBuilder("共 " + list.size() + " 个环境：");
            List<Map<String, Object>> rows = new ArrayList<>();
            for (EnvironmentView e : list) {
                boolean prod = EnvironmentEntity.PROD.equalsIgnoreCase(e.name());
                sb.append("\n- ").append(e.name())
                        .append(prod ? "（生产）" : "")
                        .append(" · id=").append(e.id());
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", e.id());
                row.put("name", e.name());
                row.put("prod", prod);
                rows.add(row);
            }
            return ToolResult.ok(sb.toString(), sb.toString(), ToolResult.data("environments", rows));
        });
    }

    static String projectId(Map<String, Object> args, ToolContext ctx) {
        String fromArgs = ToolText.str(args, "projectId");
        return fromArgs != null ? fromArgs
                : (ctx.projectId() == null || ctx.projectId().isBlank() ? null : ctx.projectId());
    }

    static ToolResult projectRequired() {
        return ToolResult.fail(ToolResult.PROJECT_REQUIRED,
                "该操作需要项目上下文。请先询问用户要操作哪个项目，或调用 list_projects 查询；禁止猜测项目 id。");
    }

    /**
     * 网关写前/确认前的对象归属反查（FR-21）：requirementId 支持内部 id 与展示编号 REQ-&lt;seq&gt;。
     * 找不到时返回参数里的 projectId（再没有就 null，由 execute 统一报 NOT_FOUND），不能让反查炸整轮。
     */
    static String requirementProject(RequirementService service, Map<String, Object> args) {
        String projectId = ToolText.str(args, "projectId");
        try {
            Long seq = args != null && args.get("seq") instanceof Number n ? n.longValue() : null;
            return service.resolveRef(projectId, ToolText.str(args, "requirementId"), seq).getProjectId();
        } catch (RuntimeException e) {
            return projectId;
        }
    }
}
