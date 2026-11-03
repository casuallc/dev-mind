package com.devmind.copilot.service;

import com.devmind.common.authz.ConfirmationVerifier;
import com.devmind.common.authz.ExecutionContext;
import com.devmind.common.authz.OperationSpec;
import com.devmind.common.authz.ResourceTarget;
import com.devmind.copilot.model.CopilotActionEntity;
import com.devmind.copilot.repo.CopilotActionRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * CAP-108 FR-06 确认凭据校验（CAP-76 确认链的服务端绑定）：
 * AI 来源（BOT/COPILOT_WEB）写命令必须挂一张<b>正在确认执行链路上</b>的确认单——
 * 属主匹配、未过期、状态 EXECUTING（CAP-76 FR-14 原子抢占保证单次消费，
 * PENDING/SUCCEEDED/FAILED 均视为伪造或复用并拒绝）。
 * 参数与目标绑定由确认链本身保证（执行用服务端快照 args，模型无法在确认后改写）。
 */
@Component
public class CopilotConfirmationVerifier implements ConfirmationVerifier {

    private final CopilotActionRepository actions;

    public CopilotConfirmationVerifier(CopilotActionRepository actions) {
        this.actions = actions;
    }

    @Override
    public String verify(ExecutionContext ctx, OperationSpec operation, ResourceTarget target) {
        String actionId = ctx == null ? null : ctx.actionId();
        if (actionId == null || actionId.isBlank()) {
            return "AI 来源写命令缺少确认凭据";
        }
        CopilotActionEntity action = actions.findById(actionId).orElse(null);
        if (action == null) {
            return "确认凭据不存在: " + actionId;
        }
        if (action.getActor() != null && ctx.username() != null
                && !action.getActor().equals(ctx.username())) {
            return "确认凭据属主不匹配";
        }
        if (!CopilotActionEntity.EXECUTING.equals(action.getStatus())) {
            return "确认凭据未在确认执行链路上（status=" + action.getStatus() + "）";
        }
        if (action.getExpiresAt() != null && action.getExpiresAt().isBefore(Instant.now())) {
            return "确认凭据已过期";
        }
        return null;
    }
}
