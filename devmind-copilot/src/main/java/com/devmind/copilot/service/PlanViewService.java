package com.devmind.copilot.service;

import com.devmind.common.dto.PageView;
import com.devmind.copilot.model.CopilotActionEntity;
import com.devmind.copilot.model.CopilotToolCallEntity;
import com.devmind.copilot.repo.CopilotActionRepository;
import com.devmind.copilot.repo.CopilotToolCallRepository;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

/**
 * 计划进度视图（CAP-76 FR-36）：复用 {@code copilot_actions}（tool_name=submit_plan）
 * + {@code copilot_tool_calls}（step_seq 步骤行），不新增计划表。
 * 旧版（M7 同步期）步骤行没有 step_seq，按 id 序与步骤快照对位兜底。
 */
@Service
public class PlanViewService {

    /** 历史（无 step_seq）步骤行的有效终态，用于对位兜底。 */
    private static final Set<String> LEGACY_STEP_STATUSES = Set.of(
            CopilotToolCallEntity.OK, CopilotToolCallEntity.FAILED, CopilotToolCallEntity.REJECTED);

    private final CopilotActionRepository actions;
    private final CopilotToolCallRepository toolCalls;

    public PlanViewService(CopilotActionRepository actions, CopilotToolCallRepository toolCalls) {
        this.actions = actions;
        this.toolCalls = toolCalls;
    }

    /** 计划列表：status/actor/keyword（短码或目标关键词）过滤，按创建时间倒序。 */
    public PageView<Map<String, Object>> listPlans(String status, String actor, String keyword,
                                                   int page, int size) {
        String kw = blankToNull(keyword);
        Specification<CopilotActionEntity> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(root.get("toolName"), PlanService.TOOL_NAME));
            if (blankToNull(status) != null) {
                ps.add(cb.equal(root.get("status"), status.trim().toUpperCase(Locale.ROOT)));
            }
            if (blankToNull(actor) != null) {
                ps.add(cb.equal(root.get("actor"), actor.trim()));
            }
            if (kw != null) {
                ps.add(cb.or(
                        cb.like(cb.upper(root.get("shortCode")),
                                "%" + kw.toUpperCase(Locale.ROOT) + "%"),
                        // args_json 是 @Lob，直接 lower() 启动期报错；cast 成 string 再 lower
                        cb.like(cb.lower(root.get("argsJson").as(String.class)),
                                "%" + kw.toLowerCase(Locale.ROOT) + "%")));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        Page<CopilotActionEntity> p = actions.findAll(spec,
                PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100),
                        Sort.by(Sort.Direction.DESC, "createdAt")));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (CopilotActionEntity action : p.getContent()) {
            rows.add(toRow(action));
        }
        return new PageView<>(rows, p.getTotalElements(), Math.max(0, page),
                Math.min(Math.max(1, size), 100));
    }

    /** 计划详情：确认单 + 步骤快照 join 步骤审计行。 */
    public Map<String, Object> planDetail(String id) {
        CopilotActionEntity action = actions.findById(id).orElse(null);
        if (action == null || !PlanService.TOOL_NAME.equals(action.getToolName())) {
            return Map.of("plan", null);
        }
        Map<String, Object> args = CopilotJson.readMap(action.getArgsJson());
        Object rawSteps = args.get("steps");
        List<Map<String, Object>> steps = new ArrayList<>();
        Map<Integer, CopilotToolCallEntity> auditBySeq = new HashMap<>();
        for (CopilotToolCallEntity row : toolCalls.findByActionIdAndStepSeqNotNullOrderByStepSeqAsc(id)) {
            auditBySeq.put(row.getStepSeq(), row);
        }
        // 旧版同步计划（M7）步骤行无 step_seq：按 id 序对位
        List<CopilotToolCallEntity> legacy = new ArrayList<>();
        for (CopilotToolCallEntity row : toolCalls.findByActionIdOrderByIdAsc(id)) {
            if (row.getStepSeq() == null && LEGACY_STEP_STATUSES.contains(row.getStatus())
                    && !PlanService.TOOL_NAME.equals(row.getToolName())) {
                legacy.add(row);
            }
        }
        if (rawSteps instanceof List<?> list) {
            int seq = 0;
            for (Object item : list) {
                seq++;
                Map<String, Object> stepRow = new LinkedHashMap<>();
                stepRow.put("seq", seq);
                CopilotToolCallEntity audit = auditBySeq.get(seq);
                if (audit == null && seq <= legacy.size()) {
                    audit = legacy.get(seq - 1);
                }
                if (item instanceof Map<?, ?> m) {
                    stepRow.put("tool", m.get("tool"));
                    stepRow.put("title", m.get("title"));
                    stepRow.put("await", m.get("await"));
                    Object stepArgs = m.get("args");
                    stepRow.put("argsJson", stepArgs instanceof Map<?, ?> a
                            ? CopilotJson.maskedArgsJson(castArgs(a), 4000) : null);
                }
                if (audit != null) {
                    stepRow.put("tool", audit.getToolName());
                    stepRow.put("status", audit.getStatus());
                    stepRow.put("resultSummary", audit.getResultSummary());
                    stepRow.put("error", audit.getError());
                    stepRow.put("durationMs", audit.getDurationMs());
                    stepRow.put("startedAt", audit.getCreatedAt());
                } else {
                    stepRow.put("status", "PENDING");
                }
                steps.add(stepRow);
            }
        }
        Map<String, Object> plan = toRow(action);
        plan.put("steps", steps);
        return Map.of("plan", plan);
    }

    private Map<String, Object> toRow(CopilotActionEntity action) {
        Map<String, Object> args = CopilotJson.readMap(action.getArgsJson());
        int total = args.get("steps") instanceof List<?> list ? list.size() : 0;
        long completed = toolCalls.countByActionIdAndStepSeqNotNullAndStatus(
                action.getId(), CopilotToolCallEntity.OK);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", action.getId());
        row.put("shortCode", action.getShortCode());
        row.put("goal", args.get("goal") instanceof String g ? g : "");
        row.put("status", action.getStatus());
        row.put("risk", action.getRisk());
        row.put("actor", action.getActor());
        row.put("projectId", action.getProjectId());
        row.put("totalSteps", total);
        row.put("completedSteps", completed);
        row.put("error", action.getError());
        row.put("createdAt", action.getCreatedAt());
        row.put("executedAt", action.getExecutedAt());
        return row;
    }

    private static Map<String, Object> castArgs(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
