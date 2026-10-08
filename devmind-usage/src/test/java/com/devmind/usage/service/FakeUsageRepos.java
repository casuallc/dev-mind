package com.devmind.usage.service;

import com.devmind.chat.model.ChatSessionEntity;
import com.devmind.session.model.SessionEntity;
import com.devmind.usage.repo.UsageChatStatsRepository;
import com.devmind.usage.repo.UsageGroupRow;
import com.devmind.usage.repo.UsageLiteRow;
import com.devmind.usage.repo.UsageSessionStatsRepository;
import com.devmind.usage.repo.UsageTotals;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用量统计仓储的内存 fake（对照 devmind-bookmark 的 JDK 动态代理先例，无 Spring 上下文）。
 * 自定义聚合方法按 JPQL 同语义在内存实现；JpaRepository 继承的未用方法直接抛 unsupported。
 */
class FakeUsageRepos {

    final List<SessionEntity> sessions = new ArrayList<>();
    final List<ChatSessionEntity> chats = new ArrayList<>();

    private static <T> T proxy(Class<T> iface, InvocationHandler handler) {
        return iface.cast(Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, handler));
    }

    private static boolean hit(Instant createdAt, String createdBy, Instant start, Instant end, String user) {
        if (start != null && (createdAt == null || createdAt.isBefore(start))) {
            return false;
        }
        if (end != null && (createdAt == null || !createdAt.isBefore(end))) {
            return false;
        }
        return user == null || user.equals(createdBy);
    }

    private static long nz(Number n) {
        return n == null ? 0 : n.longValue();
    }

    private static UsageTotals totalsOf(List<UsageLiteRow> rows) {
        long turns = 0, in = 0, out = 0, cr = 0, cw = 0;
        double cost = 0;
        for (UsageLiteRow r : rows) {
            turns += nz(r.turnCount());
            in += nz(r.inputTokens());
            out += nz(r.outputTokens());
            cost += r.costUsd() == null ? 0 : r.costUsd();
        }
        return new UsageTotals(rows.size(), turns, cost, in, out, cr, cw);
    }

    private static List<UsageGroupRow> group(List<UsageLiteRow> rows, List<String> keys) {
        Map<String, List<UsageLiteRow>> by = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            by.computeIfAbsent(keys.get(i), k -> new ArrayList<>()).add(rows.get(i));
        }
        List<UsageGroupRow> out = new ArrayList<>();
        by.forEach((k, rs) -> {
            UsageTotals t = totalsOf(rs);
            out.add(new UsageGroupRow(k, t.count(), t.turnCount(), t.costUsd(),
                    t.inputTokens(), t.outputTokens(), 0, 0));
        });
        return out;
    }

    private static UsageLiteRow lite(Instant createdAt, Double cost, Long in, Long out, Integer turns) {
        return new UsageLiteRow(createdAt, cost, in, out, turns);
    }

    UsageSessionStatsRepository sessionRepo() {
        return proxy(UsageSessionStatsRepository.class, (p, m, args) -> {
            Instant start = (Instant) args[0], end = (Instant) args[1];
            String user = (String) args[2];
            List<SessionEntity> hits = sessions.stream()
                    .filter(s -> hit(s.getCreatedAt(), s.getCreatedBy(), start, end, user)).toList();
            List<UsageLiteRow> lites = hits.stream()
                    .map(s -> lite(s.getCreatedAt(), s.getCostUsd(), s.getInputTokens(),
                            s.getOutputTokens(), s.getTurnCount())).toList();
            return switch (m.getName()) {
                case "totals" -> totalsOf(lites);
                case "groupByRequirement" -> group(lites, hits.stream().map(SessionEntity::getRequirementId).toList());
                case "groupByProject" -> group(lites, hits.stream().map(SessionEntity::getProjectId).toList());
                case "groupByModel" -> group(lites, hits.stream().map(SessionEntity::getModel).toList());
                case "groupByUser" -> group(lites, hits.stream().map(SessionEntity::getCreatedBy).toList());
                case "liteRows" -> lites;
                case "topByCost" -> hits.stream()
                        .sorted(Comparator.comparingDouble((SessionEntity s) ->
                                s.getCostUsd() == null ? 0 : s.getCostUsd()).reversed())
                        .limit(((Pageable) args[3]).getPageSize()).toList();
                default -> throw new UnsupportedOperationException(m.getName());
            };
        });
    }

    UsageChatStatsRepository chatRepo() {
        return proxy(UsageChatStatsRepository.class, (p, m, args) -> {
            Instant start = (Instant) args[0], end = (Instant) args[1];
            String user = (String) args[2];
            List<ChatSessionEntity> hits = chats.stream()
                    .filter(c -> hit(c.getCreatedAt(), c.getCreatedBy(), start, end, user)).toList();
            List<UsageLiteRow> lites = hits.stream()
                    .map(c -> lite(c.getCreatedAt(), c.getCostUsd(), c.getInputTokens(),
                            c.getOutputTokens(), c.getTurnCount())).toList();
            return switch (m.getName()) {
                case "totals" -> totalsOf(lites);
                case "groupByModel" -> group(lites, hits.stream().map(ChatSessionEntity::getModel).toList());
                case "groupByUser" -> group(lites, hits.stream().map(ChatSessionEntity::getCreatedBy).toList());
                case "liteRows" -> lites;
                case "topByCost" -> hits.stream()
                        .sorted(Comparator.comparingDouble((ChatSessionEntity c) ->
                                c.getCostUsd() == null ? 0 : c.getCostUsd()).reversed())
                        .limit(((Pageable) args[3]).getPageSize()).toList();
                default -> throw new UnsupportedOperationException(m.getName());
            };
        });
    }

    static SessionEntity session(String id, String reqId, String projectId, String model, String user,
                                 Instant createdAt, double cost, long in, long out, int turns) {
        SessionEntity s = new SessionEntity();
        s.setTaskSpec("任务 " + id);
        s.setRequirementId(reqId);
        s.setProjectId(projectId);
        s.setModel(model);
        s.setCreatedBy(user);
        s.setCreatedAt(createdAt);
        s.setCostUsd(cost);
        s.setInputTokens(in);
        s.setOutputTokens(out);
        s.setTurnCount(turns);
        return s;
    }

    static ChatSessionEntity chat(String id, String model, String user, Instant createdAt,
                                  double cost, long in, long out, int turns) {
        ChatSessionEntity c = new ChatSessionEntity();
        c.setTitle("问答 " + id);
        c.setModel(model);
        c.setCreatedBy(user);
        c.setCreatedAt(createdAt);
        c.setCostUsd(cost);
        c.setInputTokens(in);
        c.setOutputTokens(out);
        c.setTurnCount(turns);
        return c;
    }
}
