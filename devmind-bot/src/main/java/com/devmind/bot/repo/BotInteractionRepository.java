package com.devmind.bot.repo;

import com.devmind.bot.model.BotInteractionEntity;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** CAP-75 FR-13 bot_interactions 存取。 */
public interface BotInteractionRepository extends JpaRepository<BotInteractionEntity, Long> {

    /** 运维页组合查询（条件可空=不限），按 id 倒序。keyword 命中入站/出站/发送者。
     *  cast(:keyword as string)：参数只在 concat 内无列可推断，PG 会落成 bytea（前车之鉴）。 */
    @Query("""
            select a from BotInteractionEntity a
            where (:channel is null or a.channel = :channel)
              and (:status is null or a.status = :status)
              and (cast(:keyword as string) is null
                   or lower(a.inputText) like lower(concat('%', cast(:keyword as string), '%'))
                   or lower(a.replyText) like lower(concat('%', cast(:keyword as string), '%'))
                   or lower(a.senderId) like lower(concat('%', cast(:keyword as string), '%')))
            order by a.id desc
            """)
    Page<BotInteractionEntity> search(@Param("channel") String channel, @Param("status") String status,
                                      @Param("keyword") String keyword, Pageable pageable);

    /** FR-24 保留期清理：硬删 created_at 早于截止点的行，返回删除行数。
     *  派生 delete 自带不了事务，调用方（BotInteractionCleanupService.purge）必须已在事务内。 */
    long deleteByCreatedAtBefore(Instant cutoff);
}
