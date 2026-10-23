package com.devmind.chat.service;

import com.devmind.chat.dto.KnowledgeReferenceView;
import com.devmind.chat.model.ChatKnowledgeRefEntity;
import com.devmind.chat.repo.ChatKnowledgeRefRepository;
import com.devmind.common.knowledge.KnowledgeRetriever.RetrievedChunk;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CAP-84 FR-19：每轮检索引用快照。Chat 模块只依赖 common SPI，
 * 快照内容全部来自检索结果本身，不反向依赖 knowledge 实现。
 */
@Service
public class ChatKnowledgeRefService {

    private static final int MAX_NAME = 255;
    private static final int MAX_URI = 1024;
    private static final int MAX_SNIPPET = 2000;

    private final ChatKnowledgeRefRepository repo;

    public ChatKnowledgeRefService(ChatKnowledgeRefRepository repo) {
        this.repo = repo;
    }

    /** 落一轮引用（编号从 1 开始，与注入上下文里的 [S#] 对齐）。 */
    @Transactional
    public void record(String chatId, long turnSeq, List<RetrievedChunk> hits) {
        if (hits == null || hits.isEmpty() || turnSeq <= 0) {
            return;
        }
        List<ChatKnowledgeRefEntity> rows = new ArrayList<>(hits.size());
        int no = 0;
        for (RetrievedChunk hit : hits) {
            if (hit == null) {
                continue;
            }
            ChatKnowledgeRefEntity row = new ChatKnowledgeRefEntity();
            row.setChatId(chatId);
            row.setTurnSeq(turnSeq);
            row.setCitationNo(++no);
            row.setEntryId(hit.entryId());
            row.setKbId(hit.kbId());
            row.setChunkId(hit.chunkId());
            row.setEntryName(truncate(hit.entryName(), MAX_NAME));
            row.setSourceUri(truncate(hit.sourceUri(), MAX_URI));
            row.setSnippet(truncate(snippet(hit), MAX_SNIPPET));
            row.setScore(hit.score());
            row.setCreatedAt(Instant.now());
            rows.add(row);
        }
        repo.saveAll(rows);
    }

    public List<KnowledgeReferenceView> list(String chatId, Long turnSeq) {
        List<ChatKnowledgeRefEntity> rows = turnSeq == null
                ? repo.findByChatIdOrderByTurnSeqAscCitationNoAsc(chatId)
                : repo.findByChatIdAndTurnSeqOrderByCitationNoAsc(chatId, turnSeq);
        return rows.stream().map(ChatKnowledgeRefService::view).toList();
    }

    @Transactional
    public void deleteByChat(String chatId) {
        repo.deleteByChatId(chatId);
    }

    private static KnowledgeReferenceView view(ChatKnowledgeRefEntity e) {
        return new KnowledgeReferenceView(e.getId(), e.getChatId(), e.getTurnSeq(),
                e.getCitationNo() == null ? 0 : e.getCitationNo(), e.getEntryId(), e.getKbId(),
                e.getChunkId(), e.getEntryName(), e.getSourceUri(), e.getSnippet(),
                e.getScore() == null ? 0 : e.getScore());
    }

    private static String snippet(RetrievedChunk hit) {
        if (hit.snippet() != null && !hit.snippet().isBlank()) {
            return hit.snippet();
        }
        String content = hit.content() == null ? "" : hit.content().replaceAll("\\s+", " ").strip();
        return content.length() <= 300 ? content : content.substring(0, 300) + "…";
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
