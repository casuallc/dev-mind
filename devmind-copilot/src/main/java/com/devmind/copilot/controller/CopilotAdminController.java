package com.devmind.copilot.controller;

import com.devmind.common.agent.tool.PlatformTool;
import com.devmind.common.agent.tool.ToolAccessPolicy;
import com.devmind.common.agent.tool.ToolSpec;
import com.devmind.common.dto.PageView;
import com.devmind.copilot.config.CopilotProperties;
import com.devmind.copilot.model.CopilotActionEntity;
import com.devmind.copilot.model.CopilotToolCallEntity;
import com.devmind.copilot.model.CopilotTurnEntity;
import com.devmind.copilot.repo.CopilotActionRepository;
import com.devmind.copilot.repo.CopilotToolCallRepository;
import com.devmind.copilot.repo.CopilotTurnRepository;
import com.devmind.copilot.service.CopilotActionService;
import com.devmind.copilot.service.CopilotEndpointService;
import com.devmind.copilot.tool.ToolRegistry;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CAP-76 FR-30 智能体运维端点（仅 ADMIN，SecurityConfig 集中登记）：
 * 工具目录/OpenAPI、对话审计、确认单查看与兜底取消、运行配置与健康检查。
 */
@RestController
@RequestMapping("/api/copilot")
public class CopilotAdminController {

    private final CopilotProperties props;
    private final ToolRegistry registry;
    private final CopilotEndpointService endpoints;
    private final CopilotActionService actions;
    private final CopilotTurnRepository turns;
    private final CopilotActionRepository actionRepo;
    private final CopilotToolCallRepository toolCalls;
    private final ObjectProvider<ToolAccessPolicy> policies;

    public CopilotAdminController(CopilotProperties props,
                                  ToolRegistry registry,
                                  CopilotEndpointService endpoints,
                                  CopilotActionService actions,
                                  CopilotTurnRepository turns,
                                  CopilotActionRepository actionRepo,
                                  CopilotToolCallRepository toolCalls,
                                  ObjectProvider<ToolAccessPolicy> policies) {
        this.props = props;
        this.registry = registry;
        this.endpoints = endpoints;
        this.actions = actions;
        this.turns = turns;
        this.actionRepo = actionRepo;
        this.toolCalls = toolCalls;
        this.policies = policies;
    }

    @GetMapping("/admin/tools")
    public List<Map<String, Object>> tools() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PlatformTool tool : registry.all()) {
            ToolSpec spec = tool.spec();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", spec.name());
            row.put("title", spec.title());
            row.put("description", spec.description());
            row.put("risk", spec.risk().name());
            row.put("minRole", spec.minRole());
            row.put("requiresProject", spec.requiresProject());
            row.put("ownerModule", spec.ownerModule());
            row.put("since", spec.since());
            row.put("timeoutSeconds", spec.timeoutSeconds());
            row.put("parametersSchema", spec.parametersSchema());
            out.add(row);
        }
        return out;
    }

    /** OpenAPI 3.0 契约（描述用，不是可调用端点）。 */
    @GetMapping("/admin/openapi.json")
    public Map<String, Object> openApi() {
        return registry.openApi();
    }

    /** 对话审计：channel/status/actor/keyword 过滤，按时间倒序。 */
    @GetMapping("/admin/turns")
    public PageView<CopilotTurnEntity> turns(@RequestParam(required = false) String channel,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) String actor,
                                             @RequestParam(required = false) String keyword,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        String kw = blankToNull(keyword);
        Specification<CopilotTurnEntity> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (blankToNull(channel) != null) {
                ps.add(cb.equal(root.get("channel"), channel.trim()));
            }
            if (blankToNull(status) != null) {
                ps.add(cb.equal(root.get("status"), status.trim().toUpperCase(Locale.ROOT)));
            }
            if (blankToNull(actor) != null) {
                ps.add(cb.equal(root.get("actor"), actor.trim()));
            }
            if (kw != null) {
                // 只在 VARCHAR 列上做 LIKE；assistant_text 是 @Lob，直接 lower() 会在启动期报错
                ps.add(cb.like(cb.lower(root.get("userText")),
                        "%" + kw.toLowerCase(Locale.ROOT) + "%"));
            }
            return ps.isEmpty() ? cb.conjunction() : cb.and(ps.toArray(new Predicate[0]));
        };
        Page<CopilotTurnEntity> p = turns.findAll(spec, pageRequest(page, size));
        return new PageView<>(p.getContent(), p.getTotalElements(), Math.max(0, page),
                Math.min(Math.max(1, size), 100));
    }

    /** 单轮详情：turn + 工具调用审计链 + 关联确认单。 */
    @GetMapping("/admin/turns/{id}")
    public Map<String, Object> turn(@PathVariable String id) {
        CopilotTurnEntity turn = turns.findById(id).orElse(null);
        if (turn == null) {
            return Map.of("turn", null);
        }
        List<CopilotToolCallEntity> calls = toolCalls.findByTurnIdOrderByIdAsc(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("turn", turn);
        out.put("toolCalls", calls);
        out.put("actions", actionRepo.findByTurnIdOrderByCreatedAtAsc(id));
        return out;
    }

    /** 确认单审计：status/risk/actor/channel/toolName/keyword（短码）过滤。 */
    @GetMapping("/admin/actions")
    public PageView<CopilotActionEntity> actions(@RequestParam(required = false) String channel,
                                                 @RequestParam(required = false) String status,
                                                 @RequestParam(required = false) String risk,
                                                 @RequestParam(required = false) String actor,
                                                 @RequestParam(required = false) String toolName,
                                                 @RequestParam(required = false) String keyword,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        String kw = blankToNull(keyword);
        Specification<CopilotActionEntity> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (blankToNull(channel) != null) {
                ps.add(cb.equal(root.get("channel"), channel.trim()));
            }
            if (blankToNull(status) != null) {
                ps.add(cb.equal(root.get("status"), status.trim().toUpperCase(Locale.ROOT)));
            }
            if (blankToNull(risk) != null) {
                ps.add(cb.equal(root.get("risk"), risk.trim().toUpperCase(Locale.ROOT)));
            }
            if (blankToNull(actor) != null) {
                ps.add(cb.equal(root.get("actor"), actor.trim()));
            }
            if (blankToNull(toolName) != null) {
                ps.add(cb.equal(root.get("toolName"), toolName.trim()));
            }
            if (kw != null) {
                String like = "%" + kw.toUpperCase(Locale.ROOT) + "%";
                ps.add(cb.or(
                        cb.like(cb.upper(root.get("shortCode")), like),
                        cb.like(cb.lower(root.get("toolName")), "%" + kw.toLowerCase(Locale.ROOT) + "%")));
            }
            return ps.isEmpty() ? cb.conjunction() : cb.and(ps.toArray(new Predicate[0]));
        };
        Page<CopilotActionEntity> p = actionRepo.findAll(spec, pageRequest(page, size));
        return new PageView<>(p.getContent(), p.getTotalElements(), Math.max(0, page),
                Math.min(Math.max(1, size), 100));
    }

    /** ADMIN 兜底取消（同时校验状态，不绕过状态机）。 */
    @PostMapping("/admin/actions/{id}/cancel")
    public Map<String, Object> cancelAction(@PathVariable String id) {
        String reason = actions.cancel(id);
        return Map.of("ok", reason == null, "message", reason == null ? "已取消" : reason);
    }

    @GetMapping("/admin/config")
    public Map<String, Object> config() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", props.isEnabled());
        out.put("endpointId", props.getEndpointId());
        out.put("toolMode", props.getToolMode());
        out.put("maxSteps", props.getMaxSteps());
        out.put("maxToolCallsPerStep", props.getMaxToolCallsPerStep());
        out.put("maxWritesPerTurn", props.getMaxWritesPerTurn());
        out.put("planMaxSteps", props.getPlanMaxSteps());
        out.put("turnTimeoutSeconds", props.getTurnTimeoutSeconds());
        out.put("modelTimeoutSeconds", props.getModelTimeoutSeconds());
        out.put("toolTimeoutSeconds", props.getToolTimeoutSeconds());
        out.put("confirmTtlMinutes", props.getConfirmTtlMinutes());
        out.put("confirmPreviewChunkChars", props.getConfirmPreviewChunkChars());
        out.put("confirmPreviewMaxChunks", props.getConfirmPreviewMaxChunks());
        out.put("maxTurnsPerMinute", props.getMaxTurnsPerMinute());
        out.put("maxConcurrentModel", props.getMaxConcurrentModel());
        out.put("replyProgressCard", props.isReplyProgressCard());
        out.put("allowDefaultActorWrites", props.isAllowDefaultActorWrites());
        out.put("auditRetentionDays", props.getAuditRetentionDays());
        out.put("actionSweepCron", props.getActionSweepCron());
        out.put("cleanupCron", props.getCleanupCron());
        return out;
    }

    /** FR-32 健康检查：`/health` 登录用户可读（bot 自检），`/admin/health` 供运维页复用同一实现。 */
    @GetMapping({"/health", "/admin/health"})
    public Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", props.isEnabled());
        out.put("tools", registry.all().size());
        out.put("policyPresent", policies.getIfAvailable() != null);
        out.put("endpointConfigured", endpoints.configComplete());
        out.put("endpointFailure", endpoints.configureFailure());
        out.put("pendingActions", actionRepo.countByStatus(CopilotActionEntity.PENDING));
        out.put("pendingToolConfirmations",
                toolCalls.countByStatus(CopilotToolCallEntity.PENDING_CONFIRM));
        out.put("executingActions", actionRepo.countByStatus(CopilotActionEntity.EXECUTING));
        return out;
    }

    private static PageRequest pageRequest(int page, int size) {
        return PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100),
                Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
