package com.devmind.chat.service;

import com.devmind.chat.model.ChatEventEntity;
import com.devmind.chat.repo.ChatEventRepository;
import com.devmind.common.agent.SessionEvent;
import com.devmind.common.model.OpenAiCompatChatStream;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-49 多轮装配：两个来源（DB + 内存环形缓冲）按 seq 归并、注入前缀剥离、
 * 连续 assistant 合并、字符预算裁剪、历史以 user 开头。
 */
class ChatModelTurnSupplierTest {

    private static final String SYS = "SYS-PROMPT";

    /** 内存 fake（同 ChatManagerServiceTest 姿势）：只实现本类用到的查询 */
    private static ChatEventRepository repoOf(List<ChatEventEntity> rows) {
        return (ChatEventRepository) Proxy.newProxyInstance(ChatEventRepository.class.getClassLoader(),
                new Class<?>[]{ChatEventRepository.class},
                (InvocationHandler) (p, m, args) -> switch (m.getName()) {
                    case "findByChatIdAndSeqLessThanOrderBySeqDesc" -> rows.stream()
                            .filter(e -> e.getChatId().equals(args[0]) && e.getSeq() < (Long) args[1])
                            .sorted((a, b) -> Long.compare(b.getSeq(), a.getSeq()))
                            .limit(((Pageable) args[2]).getPageSize())
                            .toList();
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    private static ChatEventEntity row(String id, long seq, String type, String content) {
        ChatEventEntity e = new ChatEventEntity();
        e.setChatId(id);
        e.setSeq(seq);
        e.setType(type);
        e.setContent(content);
        e.setCreatedAt(Instant.now());
        return e;
    }

    private static SessionEvent ev(long seq, String type, String content) {
        return SessionEvent.of(seq, type, content, "model");
    }

    private static ChatModelTurnSupplier supplier(List<ChatEventEntity> rows) {
        return new ChatModelTurnSupplier("c1", SYS, repoOf(rows));
    }

    private static List<String> roles(List<OpenAiCompatChatStream.Message> msgs) {
        return msgs.stream().map(OpenAiCompatChatStream.Message::role).toList();
    }

    private static String text(List<OpenAiCompatChatStream.Message> msgs, int i) {
        return msgs.get(i).content();
    }

    @Test
    void 装配形状_system_历史_本轮提问() {
        List<ChatEventEntity> rows = List.of(
                row("c1", 1, "user", "第一问"),
                row("c1", 2, "assistant", "第一答"),
                row("c1", 3, "state", "回合完成"),
                row("c1", 4, "result", "第一答"));
        ChatModelTurnSupplier s = supplier(rows);

        List<OpenAiCompatChatStream.Message> msgs = s.buildTurn("第二问", 5, List.of());

        assertEquals(List.of("system", "user", "assistant", "user"), roles(msgs));
        assertEquals(SYS, text(msgs, 0));
        assertEquals("第一问", text(msgs, 1));
        assertEquals("第一答", text(msgs, 2));
        assertEquals("第二问", text(msgs, 3), "本轮提问永远在最后（且不被历史重复）");
    }

    @Test
    void 本轮提问不进历史_只取beforeSeq之前的() {
        // DB 里已有本轮 user（seq=5）——按 beforeSeq=5 取就把它排除在外
        List<ChatEventEntity> rows = List.of(
                row("c1", 1, "user", "第一问"),
                row("c1", 2, "assistant", "第一答"),
                row("c1", 5, "user", "本轮提问"));
        ChatModelTurnSupplier s = supplier(rows);

        List<OpenAiCompatChatStream.Message> msgs = s.buildTurn("本轮提问", 5, List.of());

        assertEquals(List.of("system", "user", "assistant", "user"), roles(msgs));
        assertEquals("本轮提问", text(msgs, 3));
        assertFalse(text(msgs, 1).contains("本轮提问"));
    }

    @Test
    void 内存环形缓冲补齐未落库的最近一轮() {
        // DB 只落到了第一轮（200ms 批量落库还没轮到第二轮），环形缓冲里有两轮
        List<ChatEventEntity> rows = List.of(
                row("c1", 1, "user", "第一问"),
                row("c1", 2, "assistant", "第一答"));
        List<SessionEvent> recent = List.of(
                ev(1, "user", "第一问"), ev(2, "assistant", "第一答"),
                ev(3, "user", "第二问"), ev(4, "assistant", "第二答"),
                ev(5, "user", "第三问"));
        ChatModelTurnSupplier s = supplier(rows);

        List<OpenAiCompatChatStream.Message> msgs = s.buildTurn("第三问", 5, recent);

        assertEquals(List.of("system", "user", "assistant", "user", "assistant", "user"), roles(msgs));
        assertEquals("第二答", text(msgs, 4), "内存里刚产出的那一轮必须补上，否则用户紧接着提问就丢上下文");
        assertEquals(6, msgs.size(), "同 seq 只出现一次（DB 与内存按 seq 归并，不是叠加）");
    }

    @Test
    void 剥掉历轮注入前缀() {
        String injected = "<knowledge-context>\n构建前必须先跑单测\n（来源：构建规范）\n\n</knowledge-context>\n\n"
                + "甲问";
        List<ChatEventEntity> rows = List.of(
                row("c1", 1, "user", injected),
                row("c1", 2, "user", "<knowledge-base>\n本会话已绑定知识库「X」\n</knowledge-base>\n\n乙问"));
        ChatModelTurnSupplier s = supplier(rows);

        List<OpenAiCompatChatStream.Message> msgs = s.buildTurn("丙问", 3, List.of());

        assertEquals("甲问", text(msgs, 1), "检索块剥掉，提问原文留下");
        assertEquals("乙问", text(msgs, 2));
    }

    @Test
    void 连续assistant以空行合并_与前端同规则() {
        List<ChatEventEntity> rows = List.of(
                row("c1", 1, "user", "问"),
                row("c1", 2, "assistant", "被中断的半句"),
                row("c1", 3, "assistant", "补答"));
        ChatModelTurnSupplier s = supplier(rows);

        List<OpenAiCompatChatStream.Message> msgs = s.buildTurn("再问", 4, List.of());

        assertEquals(List.of("system", "user", "assistant", "user"), roles(msgs));
        assertEquals("被中断的半句\n\n补答", text(msgs, 2));
    }

    @Test
    void 历史以assistant开头则丢弃到第一个user() {
        List<ChatEventEntity> rows = List.of(
                row("c1", 1, "assistant", "没有提问的回答"),
                row("c1", 2, "user", "问"),
                row("c1", 3, "assistant", "答"));
        ChatModelTurnSupplier s = supplier(rows);

        List<OpenAiCompatChatStream.Message> msgs = s.buildTurn("再问", 4, List.of());

        assertEquals(List.of("system", "user", "assistant", "user"), roles(msgs));
        assertEquals("问", text(msgs, 1));
    }

    @Test
    void 超出字符预算时从最老的一段裁() {
        String big = "x".repeat(ChatModelTurnSupplier.HISTORY_CHAR_BUDGET);
        List<ChatEventEntity> rows = List.of(
                row("c1", 1, "user", "问题一"),
                row("c1", 2, "assistant", big),
                row("c1", 3, "user", "问题二"),
                row("c1", 4, "assistant", "答案二"));
        ChatModelTurnSupplier s = supplier(rows);

        List<OpenAiCompatChatStream.Message> msgs = s.buildTurn("本轮", 5, List.of());

        assertEquals(List.of("system", "user", "assistant", "user"), roles(msgs),
                "超预算的那条（问题一 + 巨长的答案一）整对丢弃，而不是只丢一半");
        assertEquals("问题二", text(msgs, 1));
        assertEquals("答案二", text(msgs, 2));
    }

    @Test
    void 最新一条自己就超预算也必须保留() {
        String huge = "z".repeat(ChatModelTurnSupplier.HISTORY_CHAR_BUDGET + 100);
        List<ChatEventEntity> rows = List.of(
                row("c1", 1, "user", "问题一"),
                row("c1", 2, "user", huge));
        ChatModelTurnSupplier s = supplier(rows);

        List<OpenAiCompatChatStream.Message> msgs = s.buildTurn("本轮", 3, List.of());

        assertEquals(List.of("system", "user", "user"), roles(msgs));
        assertEquals(huge, text(msgs, 1), "预算是裁剪的软线，不是把最新一条也裁没了的硬线");
    }

    @Test
    void 空内容与非对话事件不进历史() {
        List<ChatEventEntity> rows = new ArrayList<>();
        rows.add(row("c1", 1, "user", "问"));
        rows.add(row("c1", 2, "assistant", "   "));      // 空增量
        rows.add(row("c1", 3, "log", "系统日志"));
        rows.add(row("c1", 4, "error", "端点 401"));
        rows.add(row("c1", 5, "tool_use", "Read()"));
        ChatModelTurnSupplier s = supplier(rows);

        List<OpenAiCompatChatStream.Message> msgs = s.buildTurn("再问", 6, List.of());

        assertEquals(List.of("system", "user", "user"), roles(msgs));
    }

    @Test
    void 历史只剩一条assistant时宁可为空_也不以assistant开头() {
        ChatModelTurnSupplier s = supplier(List.of(row("c1", 1, "assistant", "没有提问的回答")));

        List<OpenAiCompatChatStream.Message> msgs = s.buildTurn("本轮", 2, List.of());

        assertEquals(List.of("system", "user"), roles(msgs));
    }

    @Test
    void 剥离工具方法对无注入文本原样() {
        assertEquals("普通提问", ChatModelTurnSupplier.stripInjected("普通提问"));
        assertEquals("", ChatModelTurnSupplier.stripInjected(null));
        assertEquals("提问", ChatModelTurnSupplier.stripInjected(
                "  <knowledge-context>\n块\n</knowledge-context>\n\n提问"));
        assertEquals("<knowledge-context>未闭合",
                ChatModelTurnSupplier.stripInjected("<knowledge-context>未闭合"),
                "没闭合就当普通文本，不做半截切割");
    }

    @Test
    void 系统提示每轮都在() {
        ChatModelTurnSupplier s = supplier(List.of());
        List<OpenAiCompatChatStream.Message> msgs = s.buildTurn("首问", 1, List.of());
        assertEquals(List.of("system", "user"), roles(msgs));
        assertEquals(SYS, text(msgs, 0));
        assertTrue(msgs.get(1).content().contains("首问"));
    }
}
