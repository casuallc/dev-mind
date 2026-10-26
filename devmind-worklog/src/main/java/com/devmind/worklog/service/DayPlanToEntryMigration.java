package com.devmind.worklog.service;

import com.devmind.worklog.model.WorklogEntryEntity;
import com.devmind.worklog.model.WorklogPlanItemEntity;
import com.devmind.worklog.repo.WorklogEntryRepository;
import com.devmind.worklog.repo.WorklogPlanItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CAP-90 FR-06：存量 DAY 计划项 → 条目一次性迁移（启动期幂等执行，范式照 CAP-89 FR-03）。
 *
 * <p>日计划并入条目后 DAY scope 退役，存量行映射为条目：</p>
 * <ul>
 *   <li>TODO/DOING → 同状态条目（minutes=0、estimate 沿用）；DONE → DONE 条目
 *       （minutes=estimate 兜底 0）；DROPPED/CARRIED → 同状态条目（minutes=0）；</li>
 *   <li>新条目主键 = 新 uuid；两趟回填 carried_from_id（plan id → 新 uuid 映射）保滚转链
 *       （链端指向 WEEK 项或映射缺失时置空）；</li>
 *   <li>条目 plan_item_id 指向 DAY 项的一律置空（关联语义已被「计划即条目」吸收）；</li>
 *   <li>迁移完成删除全部 DAY 行。</li>
 * </ul>
 *
 * <p>幂等键：无 DAY 行即跳过；全程单事务（TransactionTemplate），崩溃重跑无半迁状态，
 * 多实例共库并发执行重复迁移无害（CAP-90 §10 R3）。</p>
 */
@Component
public class DayPlanToEntryMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DayPlanToEntryMigration.class);

    private final WorklogPlanItemRepository planRepo;
    private final WorklogEntryRepository entryRepo;
    private final TransactionTemplate tx;

    public DayPlanToEntryMigration(WorklogPlanItemRepository planRepo,
                                   WorklogEntryRepository entryRepo,
                                   PlatformTransactionManager txManager) {
        this.planRepo = planRepo;
        this.entryRepo = entryRepo;
        this.tx = new TransactionTemplate(txManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        tx.executeWithoutResult(status -> migrate());
    }

    private void migrate() {
        List<WorklogPlanItemEntity> dayItems = planRepo
                .findByScopeOrderByUserIdAscPlanDateAscIdAsc(WorklogPlanItemEntity.SCOPE_DAY);
        if (dayItems.isEmpty()) {
            return; // 幂等键：无存量 DAY 行（全新部署或已迁移），跳过
        }
        // 第一趟：逐项映射为条目，记 plan id → 新 uuid
        Map<Long, String> uuidByPlanId = new HashMap<>();
        Map<Long, WorklogEntryEntity> entryByPlanId = new HashMap<>();
        for (WorklogPlanItemEntity p : dayItems) {
            WorklogEntryEntity e = toEntry(p);
            uuidByPlanId.put(p.getId(), e.getId());
            entryByPlanId.put(p.getId(), e);
        }
        // 第二趟：回填滚转链（carried_from_id 同为 DAY 项才接得上，否则置空）
        for (WorklogPlanItemEntity p : dayItems) {
            if (p.getCarriedFromId() != null) {
                entryByPlanId.get(p.getId()).setCarriedFromId(uuidByPlanId.get(p.getCarriedFromId()));
            }
            entryRepo.save(entryByPlanId.get(p.getId()));
        }
        // 条目 plan_item_id 指向 DAY 项的一律置空（关联语义已被「计划即条目」吸收）
        for (Long planId : uuidByPlanId.keySet()) {
            List<WorklogEntryEntity> linked = entryRepo.findByPlanItemId(planId);
            linked.forEach(e -> e.setPlanItemId(null));
            entryRepo.saveAll(linked);
        }
        planRepo.deleteAll(dayItems);
        log.info("DAY 计划项→条目迁移完成（CAP-90 FR-06）: 共 {} 项", dayItems.size());
    }

    private static WorklogEntryEntity toEntry(WorklogPlanItemEntity p) {
        WorklogEntryEntity e = new WorklogEntryEntity();
        e.setId(WorklogEntryService.newId());
        e.setUserId(p.getUserId());
        e.setWorkDate(p.getPlanDate());
        e.setTitle(p.getTitle());
        e.setContent(p.getContent());
        e.setEntryType(p.getPlanType());
        e.setStatus(p.getStatus());
        e.setEstimateMinutes(p.getEstimateMinutes());
        // DONE=已发生事实：工时取预估兜底 0；计划态/终止态 minutes 归零
        e.setMinutes(WorklogPlanItemEntity.STATUS_DONE.equals(p.getStatus())
                ? (p.getEstimateMinutes() == null ? 0 : p.getEstimateMinutes()) : 0);
        e.setSource(WorklogEntryEntity.SOURCE_MANUAL);
        e.setRequirementId(p.getRequirementId());
        e.setJiraIssueKey(p.getJiraIssueKey());
        e.setCreatedAt(p.getCreatedAt());
        e.setUpdatedAt(p.getUpdatedAt());
        return e;
    }
}
