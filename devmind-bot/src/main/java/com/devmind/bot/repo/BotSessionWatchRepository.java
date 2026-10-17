package com.devmind.bot.repo;

import com.devmind.bot.model.BotSessionWatchEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

/** CAP-75 FR-20 bot_session_watches 存取。 */
public interface BotSessionWatchRepository extends JpaRepository<BotSessionWatchEntity, Long> {

    Optional<BotSessionWatchEntity> findBySessionId(String sessionId);

    /** bot /stop 停掉的会话顺手退订（用户已在场，无需再推）。 */
    void deleteBySessionId(String sessionId);

    /** FR-24 兜底：清理超期残留（会话从未结束/推送链接路失败时留下的行）。 */
    long deleteByCreatedAtBefore(Instant cutoff);
}
