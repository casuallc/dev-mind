package com.devmind.usage.service;

import com.devmind.auth.IdentityService;
import com.devmind.auth.model.UserEntity;
import com.devmind.auth.repo.UserRepository;
import com.devmind.chat.model.ChatSessionEntity;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.project.model.ProjectEntity;
import com.devmind.project.model.RequirementEntity;
import com.devmind.project.repo.ProjectRepository;
import com.devmind.project.repo.RequirementRepository;
import com.devmind.session.model.SessionEntity;
import com.devmind.usage.dto.UsageBreakdownRow;
import com.devmind.usage.dto.UsageDailyPoint;
import com.devmind.usage.dto.UsageSummary;
import com.devmind.usage.dto.UsageTopRow;
import com.devmind.usage.repo.UsageChatStatsRepository;
import com.devmind.usage.repo.UsageGroupRow;
import com.devmind.usage.repo.UsageLiteRow;
import com.devmind.usage.repo.UsageSessionStatsRepository;
import com.devmind.usage.repo.UsageTotals;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * CAP-67 用量统计聚合服务：会话 + 问答两源合并，全部只读。
 *
 * <p>可见范围照搬 CAP-62 个人数据 owner 强制口径：非 ADMIN 忽略 userId 入参强制本人；
 * ADMIN 缺省看全部。已知近似：用量按 createdAt 归属时段（累计列无逐回合时间戳）。</p>
 */
@Service
public class UsageStatsService {

    /** 需求/项目维度下问答的未归属桶标签 */
    static final String BUCKET_CHATS = "未归属（问答）";
    /** 需求维度下 requirement_id IS NULL 的会话桶标签 */
    static final String BUCKET_SESSIONS = "未归属（会话）";
    /** model 为空的分组标签 */
    static final String LABEL_DEFAULT_MODEL = "默认";

    private final UsageSessionStatsRepository sessionRepo;
    private final UsageChatStatsRepository chatRepo;
    private final RequirementRepository requirementRepo;
    private final ProjectRepository projectRepo;
    private final UserRepository userRepo;
    private final IdentityService identityService;

    public UsageStatsService(UsageSessionStatsRepository sessionRepo, UsageChatStatsRepository chatRepo,
                             RequirementRepository requirementRepo, ProjectRepository projectRepo,
                             UserRepository userRepo, IdentityService identityService) {
        this.sessionRepo = sessionRepo;
        this.chatRepo = chatRepo;
        this.requirementRepo = requirementRepo;
        this.projectRepo = projectRepo;
        this.userRepo = userRepo;
        this.identityService = identityService;
    }

    // ---------------- FR-01 总体汇总 ----------------

    public UsageSummary summary(Instant start, Instant end, String userId) {
        String user = resolveUser(userId);
        UsageTotals s = sessionRepo.totals(start, end, user);
        UsageTotals c = chatRepo.totals(start, end, user);
        return new UsageSummary(s.costUsd() + c.costUsd(),
                s.inputTokens() + c.inputTokens(), s.outputTokens() + c.outputTokens(),
                s.cacheReadTokens() + c.cacheReadTokens(), s.cacheCreationTokens() + c.cacheCreationTokens(),
                s.turnCount() + c.turnCount(), s.count(), c.count());
    }

    // ---------------- FR-02 多维分组 ----------------

    public List<UsageBreakdownRow> breakdown(String dim, Instant start, Instant end, String userId) {
        String user = resolveUser(userId);
        List<UsageBreakdownRow> rows = switch (dim == null ? "" : dim) {
            case "requirement" -> breakdownRequirement(start, end, user);
            case "project" -> breakdownProject(start, end, user);
            case "model" -> breakdownMerged(start, end, user,
                    sessionRepo.groupByModel(start, end, user), chatRepo.groupByModel(start, end, user),
                    k -> (k == null || k.isBlank()) ? LABEL_DEFAULT_MODEL : k);
            case "user" -> {
                requireAdmin();
                yield breakdownMerged(start, end, user,
                        sessionRepo.groupByUser(start, end, user), chatRepo.groupByUser(start, end, user),
                        this::userLabel);
            }
            default -> throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "dim 仅支持 requirement/project/model/user: " + dim);
        };
        rows.sort(Comparator.comparingDouble(UsageBreakdownRow::costUsd).reversed());
        return rows;
    }

    private List<UsageBreakdownRow> breakdownRequirement(Instant start, Instant end, String user) {
        List<UsageGroupRow> groups = sessionRepo.groupByRequirement(start, end, user);
        Map<String, RequirementEntity> reqs = requirementRepo.findAllById(
                        groups.stream().map(UsageGroupRow::key).filter(k -> k != null && !k.isBlank()).toList())
                .stream().collect(Collectors.toMap(RequirementEntity::getId, Function.identity()));
        List<UsageBreakdownRow> rows = new ArrayList<>();
        for (UsageGroupRow g : groups) {
            if (g.key() == null || g.key().isBlank()) {
                rows.add(toRow(null, BUCKET_SESSIONS, null, g, true));
            } else {
                RequirementEntity r = reqs.get(g.key());
                rows.add(toRow(g.key(), r != null ? r.getTitle() : g.key(),
                        r != null ? r.getProjectId() : null, g, true));
            }
        }
        UsageTotals chats = chatRepo.totals(start, end, user);
        if (chats.count() > 0) {
            rows.add(toRow(null, BUCKET_CHATS, null,
                    new UsageGroupRow(null, chats.count(), chats.turnCount(), chats.costUsd(),
                            chats.inputTokens(), chats.outputTokens(),
                            chats.cacheReadTokens(), chats.cacheCreationTokens()), false));
        }
        return rows;
    }

    private List<UsageBreakdownRow> breakdownProject(Instant start, Instant end, String user) {
        List<UsageGroupRow> groups = sessionRepo.groupByProject(start, end, user);
        Map<String, String> names = projectRepo.findAllById(
                        groups.stream().map(UsageGroupRow::key).filter(k -> k != null && !k.isBlank()).toList())
                .stream().collect(Collectors.toMap(ProjectEntity::getId, ProjectEntity::getName));
        List<UsageBreakdownRow> rows = new ArrayList<>();
        for (UsageGroupRow g : groups) {
            boolean unassigned = g.key() == null || g.key().isBlank();
            rows.add(toRow(unassigned ? null : g.key(),
                    unassigned ? BUCKET_SESSIONS : names.getOrDefault(g.key(), g.key()),
                    unassigned ? null : g.key(), g, true));
        }
        UsageTotals chats = chatRepo.totals(start, end, user);
        if (chats.count() > 0) {
            rows.add(toRow(null, BUCKET_CHATS, null,
                    new UsageGroupRow(null, chats.count(), chats.turnCount(), chats.costUsd(),
                            chats.inputTokens(), chats.outputTokens(),
                            chats.cacheReadTokens(), chats.cacheCreationTokens()), false));
        }
        return rows;
    }

    /** 两源同 key 分组合并（model/user 维度会话与问答都参与）。labeler 负责空 key 与显示名。 */
    private List<UsageBreakdownRow> breakdownMerged(Instant start, Instant end, String user,
                                                    List<UsageGroupRow> sessionGroups,
                                                    List<UsageGroupRow> chatGroups,
                                                    Function<String, String> labeler) {
        Map<String, long[]> sessionsByKey = new LinkedHashMap<>();
        Map<String, UsageGroupRow> merged = new HashMap<>();
        for (UsageGroupRow g : sessionGroups) {
            String k = normKey(g.key());
            merged.put(k, g);
            sessionsByKey.computeIfAbsent(k, x -> new long[1])[0] = g.count();
        }
        for (UsageGroupRow g : chatGroups) {
            String k = normKey(g.key());
            merged.merge(k, g, UsageGroupRow::plus);
        }
        return merged.entrySet().stream().map(e -> {
            UsageGroupRow g = e.getValue();
            long chatCount = g.count() - sessionsByKey.getOrDefault(e.getKey(), new long[1])[0];
            return new UsageBreakdownRow("null".equals(e.getKey()) ? null : e.getKey(), labeler.apply(g.key()),
                    null, sessionsByKey.getOrDefault(e.getKey(), new long[1])[0], chatCount,
                    g.turnCount(), g.costUsd(), g.inputTokens(), g.outputTokens(),
                    g.cacheReadTokens(), g.cacheCreationTokens());
        }).collect(Collectors.toCollection(ArrayList::new));
    }

    /** 合并 map 的键不能为 null（HashMap.merge 拒 null key），用哨兵归一。 */
    private static String normKey(String key) {
        return key == null ? "null" : key;
    }

    private UsageBreakdownRow toRow(String key, String label, String projectId, UsageGroupRow g,
                                    boolean sessionSide) {
        return new UsageBreakdownRow(key, label, projectId,
                sessionSide ? g.count() : 0, sessionSide ? 0 : g.count(), g.turnCount(), g.costUsd(),
                g.inputTokens(), g.outputTokens(), g.cacheReadTokens(), g.cacheCreationTokens());
    }

    private String userLabel(String username) {
        if (username == null) {
            return "未知";
        }
        return userRepo.findByUsername(username)
                .map(u -> u.getDisplayName() != null && !u.getDisplayName().isBlank()
                        ? u.getDisplayName() : u.getUsername())
                .orElse(username);
    }

    // ---------------- FR-03 每日趋势 ----------------

    public List<UsageDailyPoint> daily(int days, String userId) {
        String user = resolveUser(userId);
        ZoneId zone = ZoneId.systemDefault();
        LocalDate first = LocalDate.now().minusDays(days - 1L);
        Instant start = first.atStartOfDay(zone).toInstant();
        Map<LocalDate, double[]> cost = new HashMap<>();
        Map<LocalDate, long[]> tokensAndTurns = new HashMap<>();
        List<UsageLiteRow> rows = new ArrayList<>(sessionRepo.liteRows(start, null, user));
        rows.addAll(chatRepo.liteRows(start, null, user));
        for (UsageLiteRow r : rows) {
            if (r.createdAt() == null) {
                continue;
            }
            LocalDate day = r.createdAt().atZone(zone).toLocalDate();
            if (day.isBefore(first)) {
                continue;
            }
            cost.computeIfAbsent(day, x -> new double[1])[0] += r.costUsd() == null ? 0 : r.costUsd();
            long[] tt = tokensAndTurns.computeIfAbsent(day, x -> new long[2]);
            tt[0] += (r.inputTokens() == null ? 0 : r.inputTokens()) + (r.outputTokens() == null ? 0 : r.outputTokens());
            tt[1] += r.turnCount() == null ? 0 : r.turnCount();
        }
        List<UsageDailyPoint> out = new ArrayList<>();
        for (LocalDate d = first; !d.isAfter(LocalDate.now()); d = d.plusDays(1)) {
            double c = cost.getOrDefault(d, new double[1])[0];
            long[] tt = tokensAndTurns.getOrDefault(d, new long[2]);
            out.add(new UsageDailyPoint(d.toString(), c, tt[0], tt[1]));
        }
        return out;
    }

    // ---------------- FR-04 用量 Top 明细 ----------------

    public List<UsageTopRow> top(int limit, Instant start, Instant end, String userId) {
        String user = resolveUser(userId);
        PageRequest page = PageRequest.of(0, limit);
        List<UsageTopRow> rows = new ArrayList<>();
        for (SessionEntity s : sessionRepo.topByCost(start, end, user, page)) {
            rows.add(new UsageTopRow("SESSION", s.getId(), s.getTaskSpec(),
                    s.getRequirementId(), null, s.getProjectId(), s.getModel(), s.getCreatedBy(),
                    s.getCreatedAt(), nz(s.getTurnCount()), nz(s.getCostUsd()), nz(s.getInputTokens()),
                    nz(s.getOutputTokens()), nz(s.getCacheReadTokens()), nz(s.getCacheCreationTokens())));
        }
        for (ChatSessionEntity c : chatRepo.topByCost(start, end, user, page)) {
            rows.add(new UsageTopRow("CHAT", c.getId(), c.getTitle(),
                    null, null, null, c.getModel(), c.getCreatedBy(),
                    c.getCreatedAt(), nz(c.getTurnCount()), nz(c.getCostUsd()), nz(c.getInputTokens()),
                    nz(c.getOutputTokens()), nz(c.getCacheReadTokens()), nz(c.getCacheCreationTokens())));
        }
        rows.sort(Comparator.comparingDouble(UsageTopRow::costUsd).reversed());
        rows = rows.stream().limit(limit).collect(Collectors.toCollection(ArrayList::new));
        // 需求标题补全（内存 join，不 N+1）
        Map<String, String> titles = requirementRepo.findAllById(rows.stream()
                        .map(UsageTopRow::requirementId).filter(r -> r != null && !r.isBlank()).distinct().toList())
                .stream().collect(Collectors.toMap(RequirementEntity::getId, RequirementEntity::getTitle));
        return rows.stream().map(r -> r.requirementId() == null ? r
                        : new UsageTopRow(r.source(), r.id(), r.title(), r.requirementId(),
                        titles.get(r.requirementId()), r.projectId(), r.model(), r.createdBy(), r.createdAt(),
                        r.turnCount(), r.costUsd(), r.inputTokens(), r.outputTokens(),
                        r.cacheReadTokens(), r.cacheCreationTokens()))
                .toList();
    }

    // ---------------- 可见范围 ----------------

    /** 非 ADMIN 忽略入参强制本人；ADMIN 传值按值、缺省看全部。 */
    private String resolveUser(String requested) {
        if (!isAdmin()) {
            return identityService.currentActor();
        }
        return requested == null || requested.isBlank() ? null : requested;
    }

    private void requireAdmin() {
        if (!isAdmin()) {
            throw new DevMindException(ErrorCode.FORBIDDEN, "按用户分组仅管理员可用");
        }
    }

    private boolean isAdmin() {
        return identityService.currentUser()
                .map(u -> UserEntity.ROLE_ADMIN.equals(u.getRole()))
                .orElse(false);
    }

    private static long nz(Number n) {
        return n == null ? 0 : n.longValue();
    }

    private static double nz(Double d) {
        return d == null ? 0 : d;
    }
}
