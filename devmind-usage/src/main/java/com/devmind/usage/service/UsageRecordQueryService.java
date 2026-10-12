package com.devmind.usage.service;

import com.devmind.auth.IdentityService;
import com.devmind.chat.model.ChatSessionEntity;
import com.devmind.common.dto.PageView;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.common.usage.UsageRecordSink;
import com.devmind.session.model.SessionEntity;
import com.devmind.usage.dto.UsageHourlyPoint;
import com.devmind.usage.dto.UsageRecordRow;
import com.devmind.usage.model.UsageRecordEntity;
import com.devmind.usage.repo.UsageChatStatsRepository;
import com.devmind.usage.repo.UsageRecordHourlyRow;
import com.devmind.usage.repo.UsageRecordRepository;
import com.devmind.usage.repo.UsageSessionStatsRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 逐回合账本只读查询：请求日志分页 + 今日按小时趋势。
 *
 * <p>与 {@link UsageStatsService}（读累计列）口径互不重叠：本类只读 usage_records，
 * 上线前的历史数据不在账本里（已知限制，不做存量回填）。可见范围同 CAP-62 owner 强制。</p>
 */
@Service
public class UsageRecordQueryService {

    /** 请求日志标题截断长度：taskSpec 是 16MB 级 LOB，绝不整段发到前端 */
    static final int TITLE_MAX = 120;

    private final UsageRecordRepository recordRepo;
    private final UsageSessionStatsRepository sessionRepo;
    private final UsageChatStatsRepository chatRepo;
    private final IdentityService identityService;

    public UsageRecordQueryService(UsageRecordRepository recordRepo,
                                   UsageSessionStatsRepository sessionRepo,
                                   UsageChatStatsRepository chatRepo,
                                   IdentityService identityService) {
        this.recordRepo = recordRepo;
        this.sessionRepo = sessionRepo;
        this.chatRepo = chatRepo;
        this.identityService = identityService;
    }

    /** 请求日志：时间倒序分页，owner 强制 + source/model 可空过滤。 */
    public PageView<UsageRecordRow> records(int page, int size, String source, String model,
                                            Instant start, Instant end, String userId) {
        String owner = UsageVisibility.resolveUser(identityService, userId);
        source = normSource(source);
        model = norm(model);
        List<UsageRecordEntity> rows = recordRepo.pageMatching(start, end, owner, source, model,
                PageRequest.of(page, size));
        long total = recordRepo.countMatching(start, end, owner, source, model);

        // 页内按 source 分组批量补标题/归属（内存 join，不 N+1；标题服务端截断）
        Map<String, SessionEntity> sessions = sessionRepo.findAllById(rows.stream()
                        .filter(r -> UsageRecordSink.SOURCE_SESSION.equals(r.getSource()))
                        .map(UsageRecordEntity::getSessionId).distinct().toList())
                .stream().collect(Collectors.toMap(SessionEntity::getId, Function.identity()));
        Map<String, ChatSessionEntity> chats = chatRepo.findAllById(rows.stream()
                        .filter(r -> UsageRecordSink.SOURCE_CHAT.equals(r.getSource()))
                        .map(UsageRecordEntity::getSessionId).distinct().toList())
                .stream().collect(Collectors.toMap(ChatSessionEntity::getId, Function.identity()));

        List<UsageRecordRow> items = new ArrayList<>(rows.size());
        for (UsageRecordEntity r : rows) {
            String title = null;
            String requirementId = null;
            String projectId = null;
            if (UsageRecordSink.SOURCE_SESSION.equals(r.getSource())) {
                SessionEntity s = sessions.get(r.getSessionId());
                if (s != null) {
                    title = truncate(s.getTaskSpec());
                    requirementId = s.getRequirementId();
                    projectId = s.getProjectId();
                }
            } else {
                ChatSessionEntity c = chats.get(r.getSessionId());
                if (c != null) {
                    title = truncate(c.getTitle());
                }
            }
            items.add(new UsageRecordRow(r.getId(), r.getSource(), r.getSessionId(), title,
                    requirementId, projectId, r.getModel(), r.getOwner(), r.getCreatedAt(),
                    r.getDurationMs(), r.getCostUsd(), r.getInputTokens(), r.getOutputTokens(),
                    r.getCacheReadTokens(), r.getCacheCreationTokens(), tokensPerSec(r)));
        }
        return new PageView<>(items, total, page, size);
    }

    /** 今日按小时趋势：固定服务器当天 24 桶（含零桶），口径同 daily 的 ZoneId。 */
    public List<UsageHourlyPoint> hourly(String userId, String model) {
        String owner = UsageVisibility.resolveUser(identityService, userId);
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        Instant start = today.atStartOfDay(zone).toInstant();
        Instant end = today.plusDays(1).atStartOfDay(zone).toInstant();
        long[] tokens = new long[24];
        long[] turns = new long[24];
        for (UsageRecordHourlyRow r : recordRepo.hourlyRows(start, end, owner, null, norm(model))) {
            if (r.createdAt() == null) {
                continue;
            }
            int h = r.createdAt().atZone(zone).getHour();
            tokens[h] += (r.inputTokens() == null ? 0 : r.inputTokens())
                    + (r.outputTokens() == null ? 0 : r.outputTokens());
            turns[h]++;
        }
        List<UsageHourlyPoint> out = new ArrayList<>(24);
        for (int h = 0; h < 24; h++) {
            out.add(new UsageHourlyPoint(String.format("%02d", h), tokens[h], turns[h]));
        }
        return out;
    }

    /** 速度 = 输出 tokens/秒；耗时或输出缺失（模型执行体只报 durationMs）→ null，前端显示 - */
    private static Double tokensPerSec(UsageRecordEntity r) {
        if (r.getOutputTokens() == null || r.getDurationMs() == null || r.getDurationMs() <= 0) {
            return null;
        }
        return r.getOutputTokens() * 1000.0 / r.getDurationMs();
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        String oneLine = text.replace('\n', ' ').strip();
        return oneLine.length() <= TITLE_MAX ? oneLine : oneLine.substring(0, TITLE_MAX) + "…";
    }

    private static String normSource(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        if (!UsageRecordSink.SOURCE_SESSION.equals(source) && !UsageRecordSink.SOURCE_CHAT.equals(source)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "source 仅支持 SESSION/CHAT: " + source);
        }
        return source;
    }

    private static String norm(String v) {
        return v == null || v.isBlank() ? null : v;
    }
}
