package com.devmind.chat.service;

import com.devmind.auth.IdentityService;
import com.devmind.auth.model.UserEntity;
import com.devmind.chat.dto.KnowledgeFeedbackView;
import com.devmind.chat.dto.KnowledgeReferenceView;
import com.devmind.chat.dto.NegativeFeedbackReportView;
import com.devmind.chat.model.ChatSessionEntity;
import com.devmind.chat.model.KnowledgeFeedbackEntity;
import com.devmind.chat.model.KnowledgeFeedbackRefEntity;
import com.devmind.chat.repo.ChatSessionRepository;
import com.devmind.chat.repo.KnowledgeFeedbackRefRepository;
import com.devmind.chat.repo.KnowledgeFeedbackRepository;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CAP-84 FR-25：消息级「有用/无用 + 原因」反馈。
 *
 * <p>反馈按 (chat, turn, user) 唯一，重复提交覆盖评价与原因；引用集合落快照表，
 * 文档后续改动不影响历史统计。管理页看负面反馈 Top 文档，用于补文档/改分块。</p>
 */
@Service
public class KnowledgeFeedbackService {

    private static final int MAX_REASON = 1000;

    private final KnowledgeFeedbackRepository repo;
    private final KnowledgeFeedbackRefRepository refRepo;
    private final ChatSessionRepository chatRepo;
    private final ChatKnowledgeRefService knowledgeRefService;
    private final IdentityService identityService;

    public KnowledgeFeedbackService(KnowledgeFeedbackRepository repo,
                                    KnowledgeFeedbackRefRepository refRepo,
                                    ChatSessionRepository chatRepo,
                                    ChatKnowledgeRefService knowledgeRefService,
                                    IdentityService identityService) {
        this.repo = repo;
        this.refRepo = refRepo;
        this.chatRepo = chatRepo;
        this.knowledgeRefService = knowledgeRefService;
        this.identityService = identityService;
    }

    @Transactional
    public KnowledgeFeedbackView submit(String chatId, Long turnSeq, Boolean useful, String reason) {
        ChatSessionEntity ent = requireOwned(chatId);
        if (turnSeq == null || turnSeq <= 0) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "请指定要反馈的回答轮次");
        }
        if (useful == null) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "请选择有用或无用");
        }
        String text = trim(reason);
        if (!useful && (text == null || text.isBlank())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "请填写「无用」的具体原因，便于定位文档或分块问题");
        }
        String actor = identityService.currentActor();
        Instant now = Instant.now();
        KnowledgeFeedbackEntity row = repo
                .findByChatIdAndTurnSeqAndCreatedBy(chatId, turnSeq, actor)
                .orElseGet(KnowledgeFeedbackEntity::new);
        if (row.getId() == null) {
            row.setChatId(chatId);
            row.setTurnSeq(turnSeq);
            row.setCreatedBy(actor);
            row.setCreatedAt(now);
        } else {
            refRepo.deleteByFeedbackId(row.getId());
        }
        row.setUseful(useful);
        row.setReason(text);
        row.setMode(ent.getKnowledgeMode());
        row.setUpdatedAt(now);
        row = repo.save(row);
        saveRefs(row.getId(), knowledgeRefService.list(chatId, turnSeq));
        return view(row);
    }

    @Transactional(readOnly = true)
    public KnowledgeFeedbackView find(String chatId, Long turnSeq) {
        requireOwned(chatId);
        if (turnSeq == null) {
            return null;
        }
        return repo.findByChatIdAndTurnSeqAndCreatedBy(chatId, turnSeq, identityService.currentActor())
                .map(this::view)
                .orElse(null);
    }

    @Transactional
    public void deleteByChat(String chatId) {
        List<KnowledgeFeedbackEntity> rows = repo.findByChatId(chatId);
        if (!rows.isEmpty()) {
            refRepo.deleteByFeedbackIdIn(rows.stream().map(KnowledgeFeedbackEntity::getId).toList());
        }
        repo.deleteByChatId(chatId);
    }

    /** 负面反馈 Top 文档：区间内按文档聚合，只返回有无用反馈的文档。 */
    @Transactional(readOnly = true)
    public NegativeFeedbackReportView topNegative(int days, int limit) {
        requireAdmin();
        int window = Math.max(1, Math.min(days, 365));
        int max = Math.max(1, Math.min(limit, 100));
        Instant since = Instant.now().minus(window, ChronoUnit.DAYS);
        List<KnowledgeFeedbackEntity> all = repo.findByCreatedAtAfterOrderByCreatedAtDesc(since);
        Map<Long, List<KnowledgeFeedbackRefEntity>> refsByFeedback = refsByFeedback(
                all.stream().map(KnowledgeFeedbackEntity::getId).toList());

        Map<Long, DocStat> stats = new LinkedHashMap<>();
        long negativeTotal = 0;
        for (KnowledgeFeedbackEntity fb : all) {
            if (!fb.isUseful()) {
                negativeTotal++;
            }
            Set<Long> seen = new LinkedHashSet<>();
            for (KnowledgeFeedbackRefEntity ref : refsByFeedback.getOrDefault(fb.getId(), List.of())) {
                if (ref.getEntryId() == null || !seen.add(ref.getEntryId())) {
                    continue;
                }
                DocStat st = stats.computeIfAbsent(ref.getEntryId(), k -> new DocStat());
                st.entryId = ref.getEntryId();
                st.kbId = ref.getKbId();
                if (ref.getEntryName() != null && !ref.getEntryName().isBlank()) {
                    st.entryName = ref.getEntryName();
                }
                st.total++;
                if (!fb.isUseful()) {
                    st.negative++;
                    st.lastNegativeAt = fb.getUpdatedAt() == null ? fb.getCreatedAt() : fb.getUpdatedAt();
                    String reason = fb.getReason();
                    if (reason != null && !reason.isBlank()) {
                        st.reasons.merge(reason, 1L, Long::sum);
                    }
                }
            }
        }
        List<NegativeFeedbackReportView.Doc> docs = stats.values().stream()
                .filter(s -> s.negative > 0)
                .sorted(Comparator.comparingLong((DocStat s) -> s.negative).reversed()
                        .thenComparing(s -> s.lastNegativeAt == null ? Instant.EPOCH : s.lastNegativeAt,
                                Comparator.reverseOrder()))
                .limit(max)
                .map(DocStat::view)
                .toList();
        return new NegativeFeedbackReportView(since, all.size(), negativeTotal, docs);
    }

    private void saveRefs(Long feedbackId, List<KnowledgeReferenceView> refs) {
        if (refs == null || refs.isEmpty()) {
            return;
        }
        List<KnowledgeFeedbackRefEntity> rows = new ArrayList<>(refs.size());
        for (KnowledgeReferenceView ref : refs) {
            KnowledgeFeedbackRefEntity row = new KnowledgeFeedbackRefEntity();
            row.setFeedbackId(feedbackId);
            row.setEntryId(ref.entryId());
            row.setKbId(ref.kbId());
            row.setChunkId(ref.chunkId());
            row.setCitationNo(ref.citationNo());
            row.setEntryName(ref.entryName());
            rows.add(row);
        }
        refRepo.saveAll(rows);
    }

    private Map<Long, List<KnowledgeFeedbackRefEntity>> refsByFeedback(List<Long> feedbackIds) {
        Map<Long, List<KnowledgeFeedbackRefEntity>> map = new HashMap<>();
        if (feedbackIds.isEmpty()) {
            return map;
        }
        for (KnowledgeFeedbackRefEntity ref : refRepo.findByFeedbackIdIn(feedbackIds)) {
            map.computeIfAbsent(ref.getFeedbackId(), k -> new ArrayList<>()).add(ref);
        }
        return map;
    }

    private KnowledgeFeedbackView view(KnowledgeFeedbackEntity row) {
        List<KnowledgeFeedbackView.Ref> refs = refRepo.findByFeedbackIdIn(List.of(row.getId())).stream()
                .map(r -> new KnowledgeFeedbackView.Ref(r.getEntryId(), r.getKbId(), r.getChunkId(),
                        r.getCitationNo(), r.getEntryName()))
                .toList();
        return new KnowledgeFeedbackView(row.getId(), row.getChatId(), row.getTurnSeq(), row.isUseful(),
                row.getReason(), row.getMode(), refs, row.getCreatedBy(), row.getCreatedAt(), row.getUpdatedAt());
    }

    private ChatSessionEntity requireOwned(String chatId) {
        ChatSessionEntity ent = chatRepo.findById(chatId)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "问答不存在: " + chatId));
        String actor = identityService.currentActor();
        if (ent.getCreatedBy() != null && !ent.getCreatedBy().equals(actor) && !isAdmin()) {
            throw new DevMindException(ErrorCode.NOT_FOUND, "问答不存在: " + chatId);
        }
        return ent;
    }

    private void requireAdmin() {
        if (!isAdmin()) {
            throw new DevMindException(ErrorCode.FORBIDDEN, "仅管理员可查看负面反馈统计");
        }
    }

    private boolean isAdmin() {
        try {
            return identityService.currentUser()
                    .map(u -> UserEntity.ROLE_ADMIN.equals(u.getRole()))
                    .orElse(false);
        } catch (Exception e) {
            return false;
        }
    }

    private static String trim(String reason) {
        if (reason == null) {
            return null;
        }
        String text = reason.strip();
        return text.length() > MAX_REASON ? text.substring(0, MAX_REASON) : text;
    }

    private static final class DocStat {
        private Long entryId;
        private Long kbId;
        private String entryName;
        private long total;
        private long negative;
        private Instant lastNegativeAt;
        private final Map<String, Long> reasons = new LinkedHashMap<>();

        private NegativeFeedbackReportView.Doc view() {
            List<NegativeFeedbackReportView.ReasonCount> reasonCounts = reasons.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(3)
                    .map(e -> new NegativeFeedbackReportView.ReasonCount(e.getKey(), e.getValue()))
                    .toList();
            double rate = total == 0 ? 1.0 : Math.round((double) negative / total * 1000) / 1000.0;
            return new NegativeFeedbackReportView.Doc(entryId, entryName, kbId, negative, total, rate,
                    reasonCounts, lastNegativeAt);
        }
    }
}
