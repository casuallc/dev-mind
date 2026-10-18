package com.devmind.app.tool;

import com.devmind.build.dto.BuildView;
import com.devmind.build.service.BuildService;
import com.devmind.common.agent.tool.PlatformTool;
import com.devmind.common.agent.tool.ToolResult;
import com.devmind.common.agent.tool.ToolRisk;
import com.devmind.common.agent.tool.ToolSpec;
import com.devmind.common.agent.tool.ToolText;
import com.devmind.deploy.dto.DeploymentView;
import com.devmind.deploy.service.DeploymentService;
import com.devmind.project.ProjectService;
import com.devmind.project.RequirementService;
import com.devmind.project.dto.ProjectView;
import com.devmind.session.dto.SessionView;
import com.devmind.session.service.SessionManagerService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * CAP-76 §6.1 跨域聚合工具：项目总览。
 * 唯一允许跨模块聚合的位置（devmind-app 组装层）；只调各模块 Service，不碰 Repository。
 */
@Configuration
public class OverviewTools {

    private static final String M = "M1";

    private final ProjectService projects;
    private final RequirementService requirements;
    private final SessionManagerService sessions;
    private final BuildService builds;
    private final DeploymentService deployments;

    public OverviewTools(ProjectService projects, RequirementService requirements,
                         SessionManagerService sessions, BuildService builds,
                         DeploymentService deployments) {
        this.projects = projects;
        this.requirements = requirements;
        this.sessions = sessions;
        this.builds = builds;
        this.deployments = deployments;
    }

    @Bean
    public PlatformTool getProjectOverviewTool() {
        ToolSpec spec = new ToolSpec("get_project_overview", "项目总览",
                "汇总项目基本信息、需求总数/未完结数、会话数、构建数与最近失败构建/部署。"
                        + "用户问「项目现在什么情况」「有什么在跑/失败」时优先调用。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "projectId": {"type": "string", "maxLength": 64}
                          },
                          "required": ["projectId"]
                        }
                        """,
                ToolRisk.READ, "VIEWER", true, "devmind-app", M);
        return PlatformTool.of(spec, (call, ctx) -> {
            Map<String, Object> args = call.args();
            String projectId = ToolText.str(args, "projectId");
            if (projectId == null && ctx.projectId() != null && !ctx.projectId().isBlank()) {
                projectId = ctx.projectId();
            }
            if (projectId == null) {
                return ToolResult.fail(ToolResult.PROJECT_REQUIRED,
                        "该操作需要项目上下文。请先询问用户要操作哪个项目，或调用 list_projects 查询；禁止猜测项目 id。");
            }
            ProjectView p = projects.get(projectId);
            long reqTotal = requirements.list(projectId, null, null, null, null,
                    "seq", "desc", 0, 1).total();
            long reqOpen = requirements.list(projectId, "OPEN", null, null, null,
                    "seq", "desc", 0, 1).total();
            List<SessionView> sessionList = sessions.list(null, projectId, null, null);
            long runningSessions = sessionList.stream()
                    .filter(s -> "RUNNING".equals(s.status())).count();
            List<BuildView> buildList = builds.history(projectId, null);
            long failedBuilds = buildList.stream()
                    .filter(b -> "FAILED".equals(b.status())).count();
            var deployPage = deployments.history(projectId, null, 0, 200);
            long failedDeploys = deployPage.total() == 0 ? 0
                    : deployPage.items().stream().filter(d -> "FAILED".equals(d.status())).count();

            StringBuilder sb = new StringBuilder();
            sb.append("项目「").append(p.name()).append("」（id=").append(p.id()).append("）")
                    .append("\n状态: ").append(p.status())
                    .append(" · 默认分支: ").append(p.defaultBranch());
            if (p.kind() != null) {
                sb.append(" · 类型: ").append(p.kind());
            }
            sb.append("\n需求: 共 ").append(reqTotal).append(" 条，未完结 ").append(reqOpen).append(" 条")
                    .append("\n会话: 共 ").append(sessionList.size())
                    .append(" 个，运行中 ").append(runningSessions).append(" 个")
                    .append("\n构建: 共 ").append(buildList.size()).append(" 次，失败 ").append(failedBuilds)
                    .append(" 次\n部署: 近 ").append(deployPage.total()).append(" 条，失败 ")
                    .append(failedDeploys).append(" 条");

            List<Map<String, Object>> recentFailures = new ArrayList<>();
            buildList.stream().filter(b -> "FAILED".equals(b.status())).limit(3).forEach(b -> {
                sb.append("\n- 构建 #").append(b.id()).append(" 失败（")
                        .append(b.branch() == null ? "-" : b.branch()).append("）：")
                        .append(ToolText.oneLine(b.errorSummary(), 120));
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("type", "build");
                row.put("id", b.id());
                row.put("summary", ToolText.oneLine(b.errorSummary(), 200));
                recentFailures.add(row);
            });
            deployPage.items().stream().filter(d -> "FAILED".equals(d.status())).limit(3).forEach(d -> {
                sb.append("\n- 部署 #").append(d.id()).append(" 失败（")
                        .append(d.env() == null ? "-" : d.env()).append("）：")
                        .append(ToolText.oneLine(d.errorSummary(), 120));
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("type", "deployment");
                row.put("id", d.id());
                row.put("summary", ToolText.oneLine(d.errorSummary(), 200));
                recentFailures.add(row);
            });

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("projectId", p.id());
            data.put("name", p.name());
            data.put("status", p.status());
            data.put("requirementCount", reqTotal);
            data.put("openRequirementCount", reqOpen);
            data.put("sessionCount", sessionList.size());
            data.put("runningSessionCount", runningSessions);
            data.put("buildCount", buildList.size());
            data.put("failedBuildCount", failedBuilds);
            data.put("deploymentCount", deployPage.total());
            data.put("failedDeploymentCount", failedDeploys);
            data.put("recentFailures", recentFailures);
            return ToolResult.ok(sb.toString(), sb.toString(), data);
        });
    }
}
