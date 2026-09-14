package com.devmind.worklog.service;

import com.devmind.auth.IdentityService;
import com.devmind.worklog.dto.SettingsRequest;
import com.devmind.worklog.dto.SettingsView;
import com.devmind.worklog.model.WorklogUserSettingsEntity;
import com.devmind.worklog.repo.WorklogUserSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** CAP-28：个人工时开关设置（无设置行 = 默认全开）；CAP-41 FR-05：日报/周报格式模板。 */
@Service
public class WorklogSettingsService {

    private final WorklogUserSettingsRepository settingsRepo;
    private final IdentityService identity;

    public WorklogSettingsService(WorklogUserSettingsRepository settingsRepo, IdentityService identity) {
        this.settingsRepo = settingsRepo;
        this.identity = identity;
    }

    public SettingsView get() {
        return settingsRepo.findByUserId(identity.currentActor())
                .map(SettingsView::of).orElseGet(SettingsView::defaults);
    }

    @Transactional
    public SettingsView update(SettingsRequest req) {
        String me = identity.currentActor();
        WorklogUserSettingsEntity e = settingsRepo.findByUserId(me)
                .orElseGet(() -> {
                    WorklogUserSettingsEntity n = new WorklogUserSettingsEntity();
                    n.setUserId(me);
                    return n;
                });
        if (req.autoDaily() != null) {
            e.setAutoDaily(req.autoDaily());
        }
        if (req.autoWeekly() != null) {
            e.setAutoWeekly(req.autoWeekly());
        }
        if (req.dailyMinutesTarget() != null) {
            e.setDailyMinutesTarget(req.dailyMinutesTarget());
        }
        if (req.dailyTemplateMd() != null) {
            e.setDailyTemplateMd(normalizeTemplate(req.dailyTemplateMd()));
        }
        if (req.weeklyTemplateMd() != null) {
            e.setWeeklyTemplateMd(normalizeTemplate(req.weeklyTemplateMd()));
        }
        e.setUpdatedAt(Instant.now());
        return SettingsView.of(settingsRepo.save(e));
    }

    /** 模板取值（生成用）：无设置行或空白 → null（调用方回退内置默认）。 */
    public String templateOf(String username, boolean daily) {
        return settingsRepo.findByUserId(username)
                .map(s -> daily ? s.getDailyTemplateMd() : s.getWeeklyTemplateMd())
                .orElse(null);
    }

    /** 空白 → null（= 回退内置默认），避免空串与 null 两种语义并存。 */
    private static String normalizeTemplate(String t) {
        return t == null || t.isBlank() ? null : t;
    }
}
