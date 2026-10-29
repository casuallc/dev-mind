package com.devmind.common.knowledge;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * CAP-84 FR-24：方案沉淀的数据源 SPI。
 *
 * <p>方案正文、问题摘要与引用快照都在问答模块，而落库走 docs 的 DocumentService——
 * 由 chat 实现本接口、knowledge 消费，避免 chat 反向依赖 knowledge，也避免 knowledge 直接查问答表。</p>
 */
public interface SolutionSourceProvider {

    /**
     * @param chatId  会话 id（实现方必须校验当前用户归属）
     * @param turnSeq 指定轮次的 user 事件 seq；null = 最近一轮
     * @return 无可沉淀回答（会话不存在/无回答/无权访问）时 empty
     */
    Optional<SolutionSource> find(String chatId, Long turnSeq);

    /**
     * 一轮可沉淀的方案数据。
     *
     * @param title    会话标题（问题摘要），用于默认文档标题
     * @param question 本轮用户提问（已剥离检索注入前缀）
     * @param answer   本轮助手回答正文
     * @param model    生成所用模型名
     * @param mode     ANSWER / SOLUTION
     * @param evidence 本轮引用快照，按 citationNo 升序
     */
    record SolutionSource(String chatId, String title, String question, String answer,
                          String model, String mode, Instant generatedAt,
                          List<String> knowledgeBaseNames, List<Evidence> evidence) {

        public record Evidence(int citationNo, Long entryId, Long kbId, String entryName,
                               String sourceUri, Long chunkId, String snippet, Double score) {
        }
    }
}
