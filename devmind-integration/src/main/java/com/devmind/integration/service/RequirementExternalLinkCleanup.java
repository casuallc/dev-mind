package com.devmind.integration.service;

import com.devmind.integration.model.ExternalLinkEntity;
import com.devmind.integration.repo.ExternalLinkRepository;
import com.devmind.project.event.RequirementDeletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * REQ-27：需求删除时清掉它的全部外部关联（external_links，internalType=REQUIREMENT）。
 *
 * <p>背景死角：同步幂等完全靠 external_links——Jira 来源需求被删后 link 残留，
 * 下一轮同步命中残留行 → 刷新抛 NOT_FOUND → 永远 SKIPPED，issue 再也导不回来。
 * 本监听器保证「不再产生新残留」；存量脏数据由 {@link JiraSyncService#upsertIssue}
 * 的探活自愈覆盖。
 *
 * <p>事务口径：{@code RequirementService.delete} 是 @Transactional，且
 * {@code DomainEventPublisher.publish} 同步派发（提交前），监听器复用调用方事务，
 * 派生 deleteBy 有事务上下文。整段 try-catch 只记 warn 不抛——清理失败不该让删除本身回滚。
 */
@Component
public class RequirementExternalLinkCleanup {

    private static final Logger log = LoggerFactory.getLogger(RequirementExternalLinkCleanup.class);

    private final ExternalLinkRepository linkRepo;

    public RequirementExternalLinkCleanup(ExternalLinkRepository linkRepo) {
        this.linkRepo = linkRepo;
    }

    @EventListener
    public void onRequirementDeleted(RequirementDeletedEvent event) {
        try {
            linkRepo.deleteByInternalTypeAndInternalId(
                    ExternalLinkEntity.INTERNAL_REQUIREMENT, event.requirementId());
        } catch (RuntimeException e) {
            log.warn("需求外部关联清理失败（不影响删除）: requirement={} err={}",
                    event.requirementId(), e.getMessage());
        }
    }
}
