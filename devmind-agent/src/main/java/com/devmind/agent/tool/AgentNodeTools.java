package com.devmind.agent.tool;

import com.devmind.agent.dto.AgentNodeView;
import com.devmind.agent.service.AgentNodeService;
import com.devmind.common.agent.tool.PlatformTool;
import com.devmind.common.agent.tool.ToolResult;
import com.devmind.common.agent.tool.ToolRisk;
import com.devmind.common.agent.tool.ToolSpec;
import com.devmind.common.agent.tool.ToolText;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * CAP-76 §6.6 执行节点工具：节点在线状态查询。
 * 不返回 token 哈希/代理口令等敏感字段；只暴露调度所需信息。
 */
@Configuration
public class AgentNodeTools {

    private static final String M = "M1";

    private final AgentNodeService nodes;

    public AgentNodeTools(AgentNodeService nodes) {
        this.nodes = nodes;
    }

    @Bean
    public PlatformTool listAgentNodesTool() {
        ToolSpec spec = new ToolSpec("list_agent_nodes", "查询执行节点",
                "列出平台注册的 runner 节点（id/名称/在线状态/标签/版本/活跃会话数）。"
                        + "用户问「有哪些节点」「节点在线吗」或需要指定 agentNodeId 时调用。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "status": {"type": "string", "maxLength": 32},
                            "limit": {"type": "integer", "default": 20, "minimum": 1, "maximum": 50}
                          }
                        }
                        """,
                ToolRisk.READ, "DEVELOPER", false, "devmind-agent", M);
        return PlatformTool.of(spec, (call, ctx) -> {
            String status = ToolText.str(call.args(), "status");
            List<AgentNodeView> list = nodes.list().stream()
                    .filter(n -> status == null || status.equalsIgnoreCase(n.status()))
                    .limit(ToolText.limit(call.args()))
                    .toList();
            if (list.isEmpty()) {
                return ToolResult.ok("没有匹配的执行节点。");
            }
            StringBuilder sb = new StringBuilder("共 " + list.size() + " 个执行节点：");
            List<Map<String, Object>> rows = new ArrayList<>();
            for (AgentNodeView n : list) {
                sb.append("\n- #").append(n.id()).append(" ").append(n.name())
                        .append(" [").append(n.status()).append("]");
                if (n.isDefault()) {
                    sb.append("（平台默认）");
                }
                if (n.labels() != null && !n.labels().isBlank()) {
                    sb.append(" 标签=").append(n.labels());
                }
                if (n.activeSessionCount() != null) {
                    sb.append(" 活跃会话=").append(n.activeSessionCount());
                }
                sb.append(" 心跳=").append(ToolText.time(n.lastHeartbeatAt()));
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", n.id());
                row.put("name", n.name());
                row.put("status", n.status());
                row.put("isDefault", n.isDefault());
                row.put("labels", n.labels() == null ? "" : n.labels());
                rows.add(row);
            }
            return ToolResult.ok(sb.toString(), sb.toString(), ToolResult.data("nodes", rows));
        });
    }
}
