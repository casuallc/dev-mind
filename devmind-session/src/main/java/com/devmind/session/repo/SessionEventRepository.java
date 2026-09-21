package com.devmind.session.repo;

import com.devmind.session.model.SessionEventEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SessionEventRepository extends JpaRepository<SessionEventEntity, Long> {

    /**
     * CAP-50：补拉 seq 大于 afterSeq 的<b>最近</b>一段（倒序取、调用方反转）。
     *
     * <p>取代原先的「顺序取全量」：开流式输出后一条长回答就是几百条 {@code text_delta}，
     * 单会话事件数涨十几倍，而前端每次终态切换都会补拉一次（{@code ChatPanel}），
     * 从头取会把内存与延迟都拖垮。与 chat 侧 {@code ChatEventRepository} 同一手法。</p>
     */
    List<SessionEventEntity> findBySessionIdAndSeqGreaterThanOrderBySeqDesc(
            String sessionId, long afterSeq, Pageable limit);

    long countBySessionId(String sessionId);

    void deleteBySessionId(String sessionId);
}
